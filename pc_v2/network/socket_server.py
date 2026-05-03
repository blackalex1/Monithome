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

    async def initialize(self):
        # Подписываемся на события от плагинов
        event_bus.subscribe("plugin_state_changed", self._on_plugin_state_changed)
        event_bus.subscribe("plugin_custom_event", self._on_plugin_custom_event)
        
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
    
    # Проверяем токен
    is_trusted = token in cfg.trusted_tokens if cfg.trusted_tokens and token else False
    
    # Если токена нет или он неверный, но в конфиге прописаны доверенные токены - требуем авторизацию
    if cfg.trusted_tokens and not is_trusted:
        logger.warning(f"Unauthorized connection attempt from {sid}")
        # Пускаем в сокет, но не даем доступ к данным (не входим в 'authorized')
        # И сразу просим пароль/код
        await sio.emit('auth_required', room=sid)
        return True

    # Если токенов в конфиге нет (первый запуск) или токен верный - пускаем
    await sio.enter_room(sid, 'authorized')
    
    key = cfg.encryption_key
    if not key:
        key = SecurityManager.generate_key()
        config_manager.config.encryption_key = key
        config_manager.save()
        
    await sio.emit('auth_success', {'token': token, 'encryption_key': key}, room=sid)
    await sio.emit('authorized', {'status': 'ok'}, room=sid)
    
    logger.info(f"Client {sid} connected and authorized.")
    
    await socket_manager._send_initial_data(sid)
    return True


@sio.on("auth_attempt")
async def handle_auth_attempt(sid, data):
    # Упрощенная проверка: код доступа = токену в конфиге
    code = data.get("code")
    cfg = config_manager.get()
    
    # В v2 мы можем использовать первый токен из списка как пароль
    # Или добавить поле password в конфиг. Для начала - первый доверенный токен.
    valid_tokens = cfg.trusted_tokens
    
    if not valid_tokens or code in valid_tokens:
        # Если пароля нет или он верный
        await sio.enter_room(sid, 'authorized')
        
        # Если это новый токен, которого нет в конфиге, добавим его (если список был пуст)
        if not valid_tokens:
            config_manager.config.trusted_tokens.append(code)
            config_manager.save()
            
        await sio.emit('auth_success', {'token': code, 'encryption_key': cfg.encryption_key}, room=sid)
        await sio.emit('authorized', {'status': 'ok'}, room=sid)
        
        await socket_manager._send_initial_data(sid)
        logger.info(f"Client {sid} authorized via code.")
    else:
        await sio.emit('auth_failed', {'message': 'Invalid code'}, room=sid)
        logger.warning(f"Client {sid} failed authorization attempt.")

@sio.on("authorize")
async def handle_authorize(sid, data):
    # Android клиент часто шлет это событие после коннекта
    cfg = config_manager.get()
    token = data.get("token")
    await sio.emit('auth_success', {'token': token, 'encryption_key': cfg.encryption_key}, room=sid)
    await sio.emit('authorized', {'status': 'ok'}, room=sid)
    await socket_manager._send_initial_data(sid)

@sio.on("get_ui_config")
async def handle_get_ui_config(sid):
    # Отправляем конфигурацию плагинов для построения UI на Android
    cfg = config_manager.get()
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
                    p_cfg["active"] = d in cfg.active_plugins
                    plugins_configs.append(p_cfg)
    
    await sio.emit("ui_config", {"plugins": plugins_configs}, room=sid)

@sio.on("get_manager_data")
async def handle_get_manager_data(sid):
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
    # Перенаправляем запрос в плагин Яндекса, если он есть
    p = plugin_manager.active_plugins.get("yandex_station")
    if p:
        await p.handle_command("get_yandex_config", {"sid": sid})

@sio.event
async def disconnect(sid):
    logger.info(f"Client {sid} disconnected")

@sio.event
async def plugin_command(sid, data):
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
