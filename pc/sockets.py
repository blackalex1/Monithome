import random
import uuid
import threading
import logging
from concurrent.futures import ThreadPoolExecutor
from flask import request
from flask_socketio import emit, join_room
from config import get_master_config, save_master_config
from manager import plugins

logger = logging.getLogger("CORE")
executor = ThreadPoolExecutor(max_workers=10)
pending_pairings = {}

def register_socket_events(socketio, p_manager):
    @socketio.on('connect')
    def handle_connect(auth=None):
        sid = request.sid
        token = None
        if auth and isinstance(auth, dict):
            token = auth.get('token')
        
        master = get_master_config()
        is_localhost = request.remote_addr in ['127.0.0.1', 'localhost', '::1']
        trusted = master.get("trusted_tokens", [])
        
        if is_localhost or (token in trusted and token is not None):
            logger.info(f"Authorized device connected (sid: {sid})")
            join_room('authorized')
            
            # Подтверждаем успех авторизации для планшета
            emit('auth_success', {'token': token})
            
            # Отправляем начальный конфиг
            send_ui_config(socketio, p_manager, sid)
            p_manager.emit_event("client_connected", sid)
            send_stats_to_sid(socketio, sid, p_manager)
            
            # Отправляем все "тяжелые" начальные данные (обложки, тексты и т.д.)
            threading.Timer(1.5, send_initial_plugin_data, args=(socketio, sid, p_manager)).start()
            return True
        
        code = "".join([str(random.randint(0, 9)) for _ in range(6)])
        pending_pairings[sid] = code
        logger.info(f"New connection from {request.remote_addr} (pending pairing, code: {code})")
        socketio.emit('pairing_request', {'sid': sid, 'code': code})
        emit('auth_required', {'sid': sid})
        return True

    @socketio.on('authorize')
    def handle_authorize(data):
        sid = request.sid
        token = data.get('token')
        master = get_master_config()
        trusted = master.get("trusted_tokens", [])
        
        if token in trusted and token is not None:
            logger.info(f"Device {sid} authorized via 'authorize' event")
            join_room('authorized', namespace='/')
            emit('auth_success', {'token': token})
            p_manager.emit_event("client_connected", sid)
            send_stats_to_sid(socketio, sid, p_manager)
        else:
            logger.warning(f"Device {sid} failed 'authorize' event")
            emit('auth_failed', {'reason': 'invalid_token'})

    @socketio.on('get_manager_data')
    def handle_manager_data():
        master = get_master_config()
        emit('manager_data', {
            'master_config': master,
            'all_plugins': p_manager.get_all_plugins_info()
        })
        emit('status', {
            'status': 'online', 
            'hostname': master.get('hostname'), 
            'os': master.get('os')
        })

    @socketio.on('get_yandex_config')
    def handle_get_yandex_config(data=None):
        from manager import plugins
        logger.info(f"Tablet requested Yandex config (sid: {request.sid})")
        plugin = plugins.get("yandex_station")
        if plugin and hasattr(plugin, "_broadcast_config_to_tablet"):
            plugin._broadcast_config_to_tablet(sid=request.sid)
        else:
            logger.warning("Yandex plugin not found or doesn't support broadcast")

    @socketio.on('save_master_config')
    def handle_save_master_config(data):
        if data:
            # Увеличиваем версию конфига для принудительного обновления на клиентах
            if isinstance(data, dict):
                current_ver = data.get("_v", 0)
                data["_v"] = current_ver + 1
                
                # Обновляем язык в движке, если он изменился
                from i18n import i18n_engine
                new_lang = data.get("language")
                if new_lang:
                    i18n_engine.set_language(new_lang)
            
            save_master_config(data)
            logger.info(f"Master config updated to v{data.get('_v')} and saved")
            
            # Обновляем кэши плагинов (чтобы пересчитались локализованные названия)
            p_manager.force_refresh_all()
            send_ui_config(socketio, p_manager)

    @socketio.on('save_plugin_config')
    def handle_save_plugin_config(data):
        p_id = data.get('id')
        new_config = data.get('config')
        if p_id in plugins and new_config:
            p_instance = plugins[p_id]
            if hasattr(p_instance, 'save_config'):
                p_instance.save_config(new_config)
                logger.info(f"Config updated for plugin: {p_id}")
                send_ui_config(socketio, p_manager)

    @socketio.on('toggle_plugin')
    def handle_toggle_plugin(data):
        p_id = data.get('id')
        enabled = data.get('enabled')
        master = get_master_config()
        active = master.get("active_plugins", [])
        
        if enabled:
            if p_id not in active: active.append(p_id)
            p_manager.start_plugin(p_id)
        else:
            if p_id in active: active.remove(p_id)
            p_manager.stop_plugin(p_id)
            
        master["active_plugins"] = active
        save_master_config(master)
        send_ui_config(socketio, p_manager)

    @socketio.on('auth_attempt')
    def handle_auth_attempt(data):
        sid = request.sid
        code = data.get('code')
        if sid in pending_pairings and pending_pairings[sid] == code:
            new_token = str(uuid.uuid4())
            master = get_master_config()
            if "trusted_tokens" not in master: master["trusted_tokens"] = []
            master["trusted_tokens"].append(new_token)
            save_master_config(master)
            join_room('authorized')
            del pending_pairings[sid]
            emit('auth_success', {'token': new_token})
            logger.info(f"Device {sid} authorized successfully")
            socketio.emit('pairing_complete', {'sid': sid})
            emit('manager_data', {
                'master_config': master,
                'all_plugins': p_manager.get_all_plugins_info()
            })
            emit('status', {
                'status': 'online', 
                'hostname': master.get('hostname'), 
                'os': master.get('os')
            })
            send_ui_config(socketio, p_manager)
            send_stats_to_sid(socketio, sid, p_manager)
        else:
            emit('auth_failed', {'message': 'Invalid code'})

    @socketio.on('set_language')
    def handle_set_language(data):
        from i18n import i18n_engine
        lang = data.get('language', 'ru')
        master = get_master_config()
        master["language"] = lang
        save_master_config(master)
        
        # Обновляем глобальный язык в движке перевода
        i18n_engine.set_language(lang)
        
        # Принудительно обновляем состояние всех плагинов (чтобы пересчитались локализованные строки внутри)
        p_manager.force_refresh_all()
        
        logger.info(f"System language changed to: {lang}")
        send_ui_config(socketio, p_manager)

    @socketio.on('media_command')
    def handle_media_command(data):
        p_id = data.get('plugin_id')
        action = data.get('action')
        target = data.get('target', 'all')
        if p_id in plugins:
            p_instance = plugins[p_id]
            executor.submit(p_instance.handle_command, target, action)

    @socketio.on('plugin_command')
    def handle_plugin_command(data):
        p_id = data.get('plugin_id')
        action = data.get('action')
        p_instance = plugins.get(p_id)
        
        # Если инстанс не найден (например, плагин не активен)
        if not p_instance and action == 'get_wizard':
            if p_id in p_manager.discovered:
                from plugin_manager import instantiate_plugin
                p_instance = instantiate_plugin(p_id, p_manager)
        
        if p_instance:
            target = data.get('target', 'all')
            cmd_data = data.get('data')
            executor.submit(p_instance.handle_command, target, action, cmd_data)

    @socketio.on('apply_plugin_wizard')
    def handle_apply_wizard(data):
        p_id = data.get('plugin_id')
        selections = data.get('selections', [])
        if p_id in plugins:
            p_instance = plugins[p_id]
            # Передаем в плагин как команду сохранения
            executor.submit(p_instance.handle_command, 'pc', 'handle_wizard', selections)

    @socketio.on('disconnect')
    def handle_disconnect():
        sid = request.sid
        if sid in pending_pairings: del pending_pairings[sid]
        logger.info(f"Client {sid} disconnected")

def send_ui_config(socketio, p_manager, target_sid=None):
    p_manager.broadcast_ui(target_sid=target_sid)

def send_stats_to_sid(socketio, sid, p_manager):
    """Отправка полного текущего состояния при подключении в бинарном формате"""
    import time
    import msgpack
    payload = {
        "stats": p_manager.get_all_stats(),
        "_server_time": time.time()
    }
    try:
        binary_payload = msgpack.packb(payload, use_bin_type=True)
        socketio.emit('stats', binary_payload, room=sid)
    except Exception as e:
        logger.error(f"Failed to send binary stats to {sid}: {e}")
        try:
            socketio.emit('stats_json', payload, room=sid)
        except: pass

def send_initial_plugin_data(socketio, sid, p_manager):
    """Сбор и отправка всех накопленных данных от плагинов (обложки, тексты и т.д.)"""
    logger.info(f"Sending initial plugin data to client {sid}...")
    events = p_manager.get_all_initial_events()
    for e in events:
        p_id = e.get("plugin_id")
        event_name = e.get("event")
        data = e.get("data")
        if p_id and event_name:
            socketio.emit(f'plugin_event:{p_id}', {'event': event_name, 'data': data}, room=sid)
