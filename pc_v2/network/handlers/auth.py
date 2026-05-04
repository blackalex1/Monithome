import logging
import random
import secrets
from core.config import config_manager
from core.event_bus import event_bus

logger = logging.getLogger("SocketHandlers.Auth")

def register_auth_handlers(sio, socket_manager):
    @sio.event
    async def connect(sid, environ, auth=None):
        cfg = config_manager.get()
        token = auth.get("token") if auth else None
        
        ip = environ.get('REMOTE_ADDR', '')
        ua = environ.get('HTTP_USER_AGENT', '')
        logger.info(f"Connection: IP={ip}, Token={token[:8] if token else 'None'}..., UA={ua}")
        
        is_gui = (token == config_manager.gui_token) and (config_manager.gui_token is not None)
        is_trusted = is_gui or (token in cfg.trusted_tokens if cfg.trusted_tokens and token else False)
        
        if is_gui:
            logger.info(f"Local GUI client {sid} authorized via gui_token.")
        elif 'android' in ua.lower():
            logger.info(f"Android device detected ({sid}) from {ip}. Trusted: {is_trusted}")
        
        if not is_trusted:
            code = str(random.randint(1000, 9999))
            socket_manager.pairing_codes[sid] = code
            logger.info(f"\n" + "="*40 + f"\n[AUTH] NEW DEVICE CONNECTING!\n[AUTH] PAIRING CODE: {code}\n" + "="*40)
            await event_bus.emit("show_pairing_code", {"code": code})
            await sio.emit('auth_required', room=sid)
            return True

        await sio.enter_room(sid, 'authorized')
        logger.info(f"SocketServer: Client {sid} joined 'authorized' room")
        
        from core.security import SecurityManager
        key = config_manager.get_secret("ENCRYPTION_KEY")
        if not key:
            key = SecurityManager.generate_key()
            config_manager.set_secret("ENCRYPTION_KEY", key)
        
        await sio.emit('auth_success', {
            'token': token, 
            'encryption_key': key,
            'theme_color': cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
        }, room=sid)
        await sio.emit('authorized', {'status': 'ok'}, room=sid)
        await socket_manager._send_initial_data(sid)
        return True

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
                'theme_color': cfg.theme_color
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok'}, room=sid)
            await socket_manager._send_initial_data(sid)
            return

        if cfg.trusted_tokens and code in cfg.trusted_tokens:
            await sio.enter_room(sid, 'authorized')
            await sio.emit('auth_success', {
                'token': code, 
                'encryption_key': config_manager.get_secret("ENCRYPTION_KEY"),
                'theme_color': cfg.theme_color
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok'}, room=sid)
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
                'theme_color': cfg.theme_color
            }, room=sid)
            await sio.emit('authorized', {'status': 'ok'}, room=sid)
        else:
            await sio.emit('auth_required', room=sid)

    @sio.event
    async def disconnect(sid):
        logger.info(f"Client {sid} disconnected")
        if sid in socket_manager.pairing_codes:
            del socket_manager.pairing_codes[sid]
