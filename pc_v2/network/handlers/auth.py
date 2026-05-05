import logging
import random
import secrets
import asyncio
from core.config import config_manager
from core.event_bus import event_bus

logger = logging.getLogger("SocketHandlers.Auth")

def register_auth_handlers(sio, socket_manager):
    async def _delayed_pairing_code(sid, socket_manager, sio, ip, delay=3.0):
        try:
            if delay > 0:
                await asyncio.sleep(delay)
            
            # Проверяем, не исчез ли sid (мог отключиться)
            if sid not in socket_manager.pairing_codes:
                return

            # Проверяем комнаты (sid всегда в своей комнате, поэтому ищем 'authorized')
            try:
                rooms = sio.rooms(sid)
            except:
                rooms = []
                
            if 'authorized' not in rooms:
                # ПРОВЕРКА: Если у этого устройства (по IP или DeviceKey) уже есть 
                # хотя бы одно АВТОРИЗОВАННОЕ соединение, не показываем код.
                # Это предотвращает спам кодами при дублирующих соединениях.
                is_already_authorized = False
                try:
                    # В python-socketio (особенно в AsyncServer) надежнее всего
                    # проверять комнаты через sio.rooms(sid) для каждого активного соединения.
                    # Но нам нужно знать ВСЕ активные sids.
                    # Мы можем использовать внутренний менеджер комнат.
                    for other_sid, rooms in sio.manager.rooms.get('/', {}).items():
                        if other_sid == sid: continue
                        if 'authorized' in rooms:
                            other_env = sio.get_environ(other_sid)
                            if other_env and other_env.get('REMOTE_ADDR') == ip:
                                is_already_authorized = True
                                break
                except: pass

                if is_already_authorized:
                    logger.info(f"[AUTH] Device from {ip} already has an authorized session. Suppressing code for {sid}.")
                    if sid in socket_manager.pairing_codes:
                        del socket_manager.pairing_codes[sid]
                    return

                code = socket_manager.pairing_codes.get(sid)
                if code:
                    logger.info(f"[AUTH] Triggering pairing UI for {sid} (code: {code})")
                    # Показываем код на ПК
                    await event_bus.emit("show_pairing_code", {"code": code, "server_uuid": config_manager.config.server_uuid})
                    # Посылаем сигнал на Планшет (с данными сервера, чтобы он открыл окно ввода)
                    await sio.emit('auth_required', {
                        'server_uuid': config_manager.config.server_uuid,
                        'hostname': config_manager.config.hostname,
                        'code_shown': True # Флаг, что код уже на экране ПК
                    }, room=sid)
        except Exception as e:
            logger.error(f"Error in _delayed_pairing_code: {e}")

    @sio.event
    async def connect(sid, environ, auth=None):
        try:
            cfg = config_manager.get()
            token = auth.get("token") if auth else None
            
            ip = environ.get('REMOTE_ADDR', '')
            ua = environ.get('HTTP_USER_AGENT', '')
            logger.info(f"Connection: IP={ip}, Token={str(token)[:8] if token else 'None'}..., UA={ua}")
            
            is_gui = (token == config_manager.gui_token) and (config_manager.gui_token is not None)
            
            # Безопасная проверка доверенного токена
            trusted_list = getattr(cfg, 'trusted_tokens', []) or []
            is_trusted = is_gui or (token in trusted_list if (token and trusted_list) else False)
            
            if is_gui:
                logger.info(f"Local GUI client {sid} authorized via gui_token.")
            elif is_trusted:
                logger.info(f"Device {sid} authorized via trusted token: {token[:8]}...")
            else:
                logger.info(f"Device {sid} NOT trusted. Received token: {token[:8] if token else 'None'}... Trusted count: {len(trusted_list)}")
            
            if not is_trusted:
                ua_lower = ua.lower()
                if 'android' in ua_lower or 'okhttp' in ua_lower:
                    if sid not in socket_manager.pairing_codes:
                        # Используем переданный токен или device_id как уникальный ключ для дедупликации кодов
                        # (даже если токен еще не в списке доверенных)
                        device_key = token or auth.get("device_id") if auth else None
                        
                        existing_code = None
                        if device_key:
                            for other_sid in list(socket_manager.pairing_codes.keys()):
                                try:
                                    other_auth = sio.get_environ(other_sid).get('socketio.auth', {})
                                    other_token = other_auth.get("token") or other_auth.get("device_id")
                                    if other_token == device_key:
                                        existing_code = socket_manager.pairing_codes[other_sid]
                                        break
                                except: pass
                        
                        # Если по токену не нашли, пробуем по IP как запасной вариант
                        if not existing_code:
                            for other_sid in list(socket_manager.pairing_codes.keys()):
                                try:
                                    if sio.get_environ(other_sid).get('REMOTE_ADDR') == ip:
                                        existing_code = socket_manager.pairing_codes[other_sid]
                                        break
                                except: pass
                        
                        socket_manager.pairing_codes[sid] = existing_code or str(random.randint(1000, 9999))
                    
                    if not token:
                        # Новый планшет без токена - показываем сразу везде
                        logger.info(f"[AUTH] New Android device without token. Showing code immediately.")
                        asyncio.create_task(_delayed_pairing_code(sid, socket_manager, sio, ip, delay=0))
                    else:
                        # Планшет с токеном (возможно старым) - даем 3 секунды на авто-вход
                        logger.info(f"Android device with token {sid} connected. Waiting 3s before asking for code...")
                        asyncio.create_task(_delayed_pairing_code(sid, socket_manager, sio, ip, delay=3.0))
                else:
                    # Для обычных браузеров
                    if not token:
                        code = str(random.randint(1000, 9999))
                        socket_manager.pairing_codes[sid] = code
                        logger.info(f"[AUTH] New unknown device without token. Showing code immediately.")
                        asyncio.create_task(_delayed_pairing_code(sid, socket_manager, sio, ip, delay=0))
                    else:
                        # Используем токен или device_id для дедупликации
                        device_key = token or auth.get("device_id") if auth else None
                        existing_code = None
                        if device_key:
                            for other_sid in list(socket_manager.pairing_codes.keys()):
                                try:
                                    other_auth = sio.get_environ(other_sid).get('socketio.auth', {})
                                    other_token = other_auth.get("token") or other_auth.get("device_id")
                                    if other_token == device_key:
                                        existing_code = socket_manager.pairing_codes[other_sid]
                                        break
                                except: pass
                        
                        if not existing_code:
                            for other_sid in list(socket_manager.pairing_codes.keys()):
                                try:
                                    if sio.get_environ(other_sid).get('REMOTE_ADDR') == ip:
                                        existing_code = socket_manager.pairing_codes[other_sid]
                                        break
                                except: pass
                        
                        socket_manager.pairing_codes[sid] = existing_code or str(random.randint(1000, 9999))
                        asyncio.create_task(_delayed_pairing_code(sid, socket_manager, sio, ip, delay=3.0))
                
                return True

            await sio.enter_room(sid, 'authorized')
            logger.info(f"SocketServer: Client {sid} joined 'authorized' room")
            
            from core.security import SecurityManager
            key = config_manager.get_secret("ENCRYPTION_KEY") or SecurityManager.generate_key()
            if not config_manager.get_secret("ENCRYPTION_KEY"):
                config_manager.set_secret("ENCRYPTION_KEY", key)
            
            await sio.emit('auth_success', {
                'token': token, 
                'encryption_key': key,
                'theme_color': getattr(cfg, 'theme_color', "0xFF22C55E"),
                'server_uuid': config_manager.config.server_uuid
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok', 'server_uuid': config_manager.config.server_uuid}, room=sid)
            
            # Скрываем код и чистим другие запросы с этого же IP (дубликаты)
            sids_to_clear = [sid]
            try:
                for other_sid in list(socket_manager.pairing_codes.keys()):
                    if other_sid == sid: continue
                    other_env = sio.get_environ(other_sid)
                    if other_env and other_env.get('REMOTE_ADDR') == ip:
                        sids_to_clear.append(other_sid)
            except: pass
            
            for s_id in sids_to_clear:
                if s_id in socket_manager.pairing_codes:
                    del socket_manager.pairing_codes[s_id]

            if not socket_manager.pairing_codes:
                await event_bus.emit("hide_pairing_code")
                
            await socket_manager._send_initial_data(sid)
            return True
        except Exception as e:
            logger.error(f"Critical error in connect handler: {e}")
            import traceback
            logger.error(traceback.format_exc())
            return False

    @sio.on("auth_attempt")
    async def handle_auth_attempt(sid, data):
        code = str(data.get("code", ""))
        cfg = config_manager.get()
        expected_code = socket_manager.pairing_codes.get(sid)
        
        if expected_code and code == expected_code:
            logger.info(f"[AUTH] Pairing successful for {sid}")
            new_token = secrets.token_hex(16)
            if new_token not in config_manager.config.trusted_tokens:
                config_manager.config.trusted_tokens.append(new_token)
                config_manager.save()
            
            del socket_manager.pairing_codes[sid]
            await sio.enter_room(sid, 'authorized')
            
            from core.security import SecurityManager
            key = config_manager.get_secret("ENCRYPTION_KEY") or SecurityManager.generate_key()
            if not config_manager.get_secret("ENCRYPTION_KEY"):
                config_manager.set_secret("ENCRYPTION_KEY", key)
                
            await sio.emit('auth_success', {
                'token': new_token, 
                'encryption_key': key,
                'theme_color': cfg.theme_color,
                'server_uuid': config_manager.config.server_uuid
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok', 'server_uuid': config_manager.config.server_uuid}, room=sid)
            
            if not socket_manager.pairing_codes:
                await event_bus.emit("hide_pairing_code")
                
            await socket_manager._send_initial_data(sid)
            return

        if cfg.trusted_tokens and code in cfg.trusted_tokens:
            await sio.enter_room(sid, 'authorized')
            await sio.emit('auth_success', {
                'token': code, 
                'encryption_key': config_manager.get_secret("ENCRYPTION_KEY"),
                'theme_color': cfg.theme_color,
                'server_uuid': config_manager.config.server_uuid
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok', 'server_uuid': config_manager.config.server_uuid}, room=sid)
            
            if not socket_manager.pairing_codes:
                await event_bus.emit("hide_pairing_code")
                
            await socket_manager._send_initial_data(sid)
        else:
            await sio.emit('auth_error', {'message': 'Invalid code or token'}, room=sid)

    @sio.on("authorize")
    async def handle_authorize(sid, data):
        token = data.get("token") if data else None
        cfg = config_manager.get()
        is_gui = (token == config_manager.gui_token) and (config_manager.gui_token is not None)
        is_trusted = is_gui or (token in cfg.trusted_tokens if cfg.trusted_tokens and token else False)
        
        if is_trusted:
            await sio.enter_room(sid, 'authorized')
            from core.security import SecurityManager
            key = config_manager.get_secret("ENCRYPTION_KEY") or SecurityManager.generate_key()
            if not config_manager.get_secret("ENCRYPTION_KEY"):
                config_manager.set_secret("ENCRYPTION_KEY", key)
            await sio.emit('auth_success', {
                'token': token, 
                'encryption_key': key,
                'theme_color': cfg.theme_color,
                'server_uuid': config_manager.config.server_uuid
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok', 'server_uuid': config_manager.config.server_uuid}, room=sid)
            
            if not socket_manager.pairing_codes:
                await event_bus.emit("hide_pairing_code")
        else:
            await sio.emit('auth_required', room=sid)

    @sio.event
    async def disconnect(sid):
        logger.info(f"Client {sid} disconnected")
        if sid in socket_manager.pairing_codes:
            del socket_manager.pairing_codes[sid]
