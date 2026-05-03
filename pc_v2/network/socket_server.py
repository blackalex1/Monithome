import asyncio
import logging
import time
import msgpack
import socketio
import os
from typing import Dict, Any

from core.config import config_manager
from core.security import SecurityManager
from core.event_bus import event_bus
from plugin_engine.manager import plugin_manager

logger = logging.getLogger("SocketServer")

# Асинхронный сервер Socket.IO (разрешаем CORS для тестов)
sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')

class SocketServerManager:
    """Управляет подпиской на шину событий и рассылкой через Socket.IO"""
    def __init__(self):
        self.sio = sio
        # Кэш состояния для батчинга
        self._state_cache: Dict[str, Any] = {}
        self._dirty = False
        self.pairing_codes = {} # sid -> 4-digit code

    async def initialize(self):
        # Подписываемся на события от плагинов
        event_bus.subscribe("plugin_state_changed", self._on_plugin_state_changed)
        event_bus.subscribe("plugin_custom_event", self._on_plugin_custom_event)
        event_bus.subscribe("ui_config_changed", self._on_ui_config_changed)
        
        # Запускаем фоновую рассылку (батчинг)
        asyncio.create_task(self._broadcast_loop())

    async def _on_plugin_state_changed(self, payload: Dict[str, Any]):
        p_id = payload["plugin_id"]
        state = payload["state"]
        self._state_cache[p_id] = state
        self._dirty = True

    async def _on_plugin_custom_event(self, payload: Dict[str, Any]):
        p_id = payload["plugin_id"]
        event_name = payload["event"]
        data = payload["data"]
        room = payload.get("room", "authorized")
        
        # Отправляем как специфичное событие плагина (стандарт v2)
        await self.sio.emit(f"plugin_event:{p_id}", {"event": event_name, "data": data}, room=room)
        
        # Для обратной совместимости с планшетом отправляем yandex_config как топ-левел событие
        if event_name == "yandex_config":
            await self.sio.emit("yandex_config", data, room=room)

    async def _on_ui_config_changed(self, payload: Dict[str, Any]):
        p_id = payload.get("plugin_id")
        logger.info(f"UI Config changed on server (plugin: {p_id}). Broadcasting to all clients.")
        await handle_get_ui_config(None) # None отправит всем авторизованным
        
        # Если изменились настройки Яндекса, принудительно обновляем конфиг на планшете
        if p_id == "yandex_station" or p_id is None:
            await handle_get_yandex_config(None)

    async def _broadcast_loop(self):
        """Рассылка MessagePack/JSON данных с частотой 10Hz"""
        while True:
            await asyncio.sleep(0.1) # 100ms
            if self._dirty:
                payload = {
                    "stats": self._state_cache,
                    "_server_time": time.time()
                }
                self._dirty = False
                
                try:
                    # MessagePack для Android (быстро)
                    binary_payload = msgpack.packb(payload, use_bin_type=True)
                    await self.sio.emit('stats', binary_payload, room='authorized')
                    
                    # JSON для веб-клиентов (если они еще не умеют MessagePack)
                    await self.sio.emit('stats_json', payload, room='authorized')
                except Exception as e:
                    logger.error(f"Broadcast error: {e}")

    async def _send_initial_data(self, sid):
        """Отправляет все начальные данные новому клиенту"""
        await handle_get_ui_config(sid)
        await handle_get_manager_data(sid)
        await handle_get_yandex_config(sid)
        
        if self._state_cache:
            payload = {
                "stats": self._state_cache,
                "_server_time": time.time()
            }
            try:
                binary_payload = msgpack.packb(payload, use_bin_type=True)
                await self.sio.emit('stats', binary_payload, room=sid)
                await self.sio.emit('stats_json', payload, room=sid)
            except Exception as e:
                logger.error(f"Error sending initial stats to {sid}: {e}")

socket_manager = SocketServerManager()

# --- Socket.IO Event Handlers ---

@sio.event
async def connect(sid, environ, auth=None):
    cfg = config_manager.get()
    token = auth.get("token") if auth else None
    
    # Логируем детали подключения
    ip = environ.get('REMOTE_ADDR', '')
    ua = environ.get('HTTP_USER_AGENT', '')
    logger.info(f"Connection: IP={ip}, Token={token[:8] if token else 'None'}..., UA={ua}")
    
    is_gui = (token == config_manager.gui_token) and (config_manager.gui_token is not None)
    is_trusted = is_gui or (token in cfg.trusted_tokens if cfg.trusted_tokens and token else False)
    
    if is_gui:
        logger.info(f"Local GUI client {sid} authorized via gui_token.")
    elif 'android' in ua.lower():
        logger.info(f"Android device detected ({sid}) from {ip}. Trusted: {is_trusted}")
    
    # Если токена нет или он неверный - требуем сопряжение/код
    if not is_trusted:
        import random
        code = str(random.randint(1000, 9999))
        socket_manager.pairing_codes[sid] = code
        
        logger.info(f"\n" + "="*40 + f"\n[AUTH] NEW DEVICE CONNECTING!\n[AUTH] PAIRING CODE: {code}\n" + "="*40)
        
        # Отправляем в шину событий, чтобы GUI на ПК мог показать окно
        from core.event_bus import event_bus
        await event_bus.emit("show_pairing_code", {"code": code})
        
        await sio.emit('auth_required', room=sid)
        return True # ВАЖНО: Прерываем выполнение здесь!

    # Если токенов в конфиге нет (первый запуск) или токен верный - пускаем
    await sio.enter_room(sid, 'authorized')
    
    key = cfg.encryption_key
    if not key:
        from core.security import SecurityManager
        key = SecurityManager.generate_key()
        config_manager.save_secret("ENCRYPTION_KEY", key)
    
    await sio.emit('auth_success', {
        'token': token, 
        'encryption_key': key,
        'theme_color': cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
    }, room=sid)
    await sio.emit('authorized', {'status': 'ok'}, room=sid)
    
    logger.info(f"Client {sid} authorized and ready.")
    
    await socket_manager._send_initial_data(sid)
    return True


@sio.on("auth_attempt")
async def handle_auth_attempt(sid, data):
    code = str(data.get("code", ""))
    cfg = config_manager.get()
    
    # 1. Сначала проверяем, не является ли это кодом сопряжения для нового устройства
    expected_code = socket_manager.pairing_codes.get(sid)
    
    if expected_code and code == expected_code:
        logger.info(f"[AUTH] Pairing successful for {sid}")
        
        # Генерируем новый постоянный токен для этого устройства
        import secrets
        new_token = secrets.token_hex(16)
        
        # Сохраняем токен (в .env через save_secret)
        config_manager.save_secret("TRUSTED_TOKENS", new_token)
        
        # Удаляем временный код сопряжения
        del socket_manager.pairing_codes[sid]
        
        # Авторизуем
        await sio.enter_room(sid, 'authorized')
        
        # Отправляем успех с НОВЫМ токеном
        key = cfg.encryption_key
        if not key:
            from core.security import SecurityManager
            key = SecurityManager.generate_key()
            config_manager.save_secret("ENCRYPTION_KEY", key)
            
        await sio.emit('auth_success', {
            'token': new_token, 
            'encryption_key': key,
            'theme_color': cfg.theme_color
        }, room=sid)
        await sio.emit('authorized', {'status': 'ok'}, room=sid)
        await socket_manager._send_initial_data(sid)
        return

    # 2. Если это не код сопряжения, проверяем, не является ли это существующим токеном
    if cfg.trusted_tokens and code in cfg.trusted_tokens:
        await sio.enter_room(sid, 'authorized')
        await sio.emit('auth_success', {
            'token': code, 
            'encryption_key': cfg.encryption_key,
            'theme_color': cfg.theme_color
        }, room=sid)
        await sio.emit('authorized', {'status': 'ok'}, room=sid)
        await socket_manager._send_initial_data(sid)
        logger.info(f"Client {sid} authorized via existing token.")
    else:
        logger.warning(f"[AUTH] Invalid code/token from {sid}: {code}")
        await sio.emit('auth_error', {'message': 'Invalid code or token'}, room=sid)

async def check_auth(sid):
    """Проверяет, авторизован ли клиент (находится ли он в комнате authorized)"""
    if 'authorized' not in sio.rooms(sid):
        logger.warning(f"Unauthorized access attempt from {sid}. Blocking.")
        await sio.emit('auth_required', room=sid)
        return False
    return True

@sio.on("authorize")
async def handle_authorize(sid, data):
    """Событие авторизации (используется Android и Веб клиентами)"""
    token = data.get("token") if data else None
    cfg = config_manager.get()
    
    # Проверяем токен (с учетом GUI_TOKEN и списка доверенных)
    is_gui = (token == config_manager.gui_token) and (config_manager.gui_token is not None)
    is_trusted = is_gui or (token in cfg.trusted_tokens if cfg.trusted_tokens and token else False)
    
    if is_trusted:
        await sio.enter_room(sid, 'authorized')
        
        # Получаем/генерируем ключ шифрования
        key = cfg.encryption_key
        if not key:
            from core.security import SecurityManager
            key = SecurityManager.generate_key()
            config_manager.save_secret("ENCRYPTION_KEY", key)
            
        await sio.emit('auth_success', {
            'token': token, 
            'encryption_key': key,
            'theme_color': cfg.theme_color
        }, room=sid)
        await sio.emit('authorized', {'status': 'ok'}, room=sid)
        logger.info(f"Client {sid} authorized successfully.")
    else:
        logger.warning(f"Invalid token provided by {sid}. Connection rejected.")
        await sio.emit('auth_required', room=sid)

@sio.on("get_ui_config")
async def handle_get_ui_config(sid):
    if sid is not None and not await check_auth(sid): return
    # Отправляем конфигурацию плагинов для построения UI на Android
    cfg = config_manager.get()
    lang = cfg.language or "ru"
    
    # Загружаем переводы
    translations = {}
    lang_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "web", "languages", f"{lang}.json")
    if os.path.exists(lang_path):
        try:
            with open(lang_path, "r", encoding="utf-8") as f:
                import json
                translations = json.load(f)
        except Exception as e:
            logger.error(f"Error loading translation for {lang}: {e}")

    plugins_configs = []
    
    # Получаем список всех доступных плагинов
    plugins_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "plugins")
    if os.path.exists(plugins_dir):
        for d in os.listdir(plugins_dir):
            if os.path.isdir(os.path.join(plugins_dir, d)):
                p = plugin_manager.active_plugins.get(d)
                p_cfg = {}
                if p:
                    p_cfg = p.get_config()
                else:
                    # Если плагин не запущен, читаем его конфиг с диска
                    p_cfg_path = os.path.join(plugins_dir, d, "config.json")
                    if os.path.exists(p_cfg_path):
                        with open(p_cfg_path, "r", encoding="utf-8") as f:
                            import json
                            p_cfg = json.load(f)
                
                if p_cfg:
                    p_cfg["active"] = d in plugin_manager.active_plugins
                    
                    # Применяем локализацию
                    p_id = p_cfg.get("id", d)
                    p_cfg["name"] = translations.get(f"plugin_name_{p_id}", p_cfg.get("name", p_id))
                    p_cfg["description"] = translations.get(f"plugin_desc_{p_id}", p_cfg.get("description", ""))
                    
                    plugins_configs.append(p_cfg)
    
    color = cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
    logger.info(f"Emitting ui_config. Theme color: {color}, Lang: {lang}, Room: {sid or 'authorized'}")
    await sio.emit("ui_config", {
        "plugins": plugins_configs,
        "theme_color": color,
        "translations": translations,
        "language": lang
    }, room=sid or 'authorized')

@sio.on("get_manager_data")
async def handle_get_manager_data(sid):
    if sid is not None and not await check_auth(sid): return
    # Android клиент просит список плагинов
    plugins = []
    cfg = config_manager.get()
    
    # Получаем список всех папок в plugins/
    plugins_dir = os.path.join(os.path.dirname(os.path.dirname(__file__)), "plugins")
    if os.path.exists(plugins_dir):
        for d in os.listdir(plugins_dir):
            if os.path.isdir(os.path.join(plugins_dir, d)):
                is_active = d in cfg.active_plugins
                plugins.append({"id": d, "active": is_active})
    
    await sio.emit("manager_data", plugins, room=sid)

@sio.on("get_yandex_config")
async def handle_get_yandex_config(sid):
    if sid is not None and not await check_auth(sid): return
    # Перенаправляем запрос в плагин Яндекса, если он есть
    p = plugin_manager.active_plugins.get("yandex_station")
    if p:
        await p.handle_command("get_yandex_config", {"sid": sid})

@sio.event
async def disconnect(sid):
    logger.info(f"Client {sid} disconnected")

@sio.event
async def plugin_command(sid, data):
    if not await check_auth(sid): return
    p_id = data.get('plugin_id')
    action = data.get('action')
    target = data.get('target')
    p_data = data.get('data')
    
    # Объединяем target и data в один объект для плагина
    final_data = {}
    if isinstance(p_data, dict):
        final_data.update(p_data)
    elif p_data is not None:
        final_data['value'] = p_data
        
    if target:
        final_data['device_id'] = target
    
    if p_id and action:
        # Маршрутизируем команду в плагин-движок
        await plugin_manager.handle_command(p_id, action, final_data)
