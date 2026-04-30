import random
import uuid
import threading
import logging
from concurrent.futures import ThreadPoolExecutor
from flask import request
from flask_socketio import emit, join_room
from config import get_master_config, save_master_config
from manager import plugins, get_all_plugins_info

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
        is_localhost = request.remote_addr == '127.0.0.1' or request.remote_addr == 'localhost'
        
        if is_localhost:
            logger.info(f"Local Manager UI connected (sid: {sid})")
            join_room('authorized')
            emit('manager_data', {
                'master_config': master,
                'all_plugins': get_all_plugins_info()
            })
            emit('status', {
                'status': 'online', 
                'hostname': master.get('hostname'), 
                'os': master.get('os')
            })
            send_ui_config(socketio, p_manager)
            send_stats_to_sid(socketio, sid, p_manager)
            # Задержка: клиент должен успеть зарегистрировать plugin_event-листенеры
            threading.Timer(2.0, send_covers_to_sid, args=(socketio, sid, p_manager)).start()
            threading.Timer(3.0, send_lyrics_to_sid, args=(socketio, sid, p_manager)).start()
            return True

        trusted = master.get("trusted_tokens", [])
        if token in trusted and token is not None:
            logger.info(f"Authorized device connected (sid: {sid})")
            join_room('authorized')
            emit('manager_data', {
                'master_config': master,
                'all_plugins': get_all_plugins_info()
            })
            emit('status', {
                'status': 'online', 
                'hostname': master.get('hostname'), 
                'os': master.get('os')
            })
            send_ui_config(socketio, p_manager)
            send_stats_to_sid(socketio, sid, p_manager)
            # Задержка: клиент должен успеть зарегистрировать plugin_event-листенеры
            threading.Timer(2.0, send_covers_to_sid, args=(socketio, sid, p_manager)).start()
            threading.Timer(3.0, send_lyrics_to_sid, args=(socketio, sid, p_manager)).start()
            return True
        
        code = "".join([str(random.randint(0, 9)) for _ in range(6)])
        pending_pairings[sid] = code
        logger.info(f"New connection from {request.remote_addr} (pending pairing, code: {code})")
        socketio.emit('pairing_request', {'sid': sid, 'code': code})
        emit('auth_required', {'sid': sid})
        return True

    @socketio.on('get_manager_data')
    def handle_manager_data():
        master = get_master_config()
        emit('manager_data', {
            'master_config': master,
            'all_plugins': get_all_plugins_info()
        })
        emit('status', {
            'status': 'online', 
            'hostname': master.get('hostname'), 
            'os': master.get('os')
        })

    @socketio.on('save_master_config')
    def handle_save_master_config(data):
        if data:
            save_master_config(data)
            logger.info("Master config updated and saved")
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
                'all_plugins': get_all_plugins_info()
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
        if p_id in plugins:
            p_instance = plugins[p_id]
            if action == 'get_wizard' and hasattr(p_instance, 'get_wizard_data'):
                wizard = p_instance.get_wizard_data()
                active_items = []
                if hasattr(p_instance, 'get_active_items'):
                    active_items = p_instance.get_active_items()
                
                emit('wizard_data', {
                    'plugin_id': p_id,
                    'wizard': wizard,
                    'active_items': active_items
                })
            else:
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

def send_ui_config(socketio, p_manager):
    p_manager.broadcast_ui()

def send_stats_to_sid(socketio, sid, p_manager):
    """Отправка полного текущего состояния при подключении в бинарном формате"""
    import time
    import msgpack
    payload = {
        "stats": p_manager.get_all_stats(),
        "_server_time": time.time()
    }
    binary_payload = msgpack.packb(payload, use_bin_type=True)
    socketio.emit('stats', binary_payload, room=sid)

def send_covers_to_sid(socketio, sid, p_manager):
    """Повторная отправка всех обложек при подключении — через JSON, минуя msgpack"""
    from manager import plugins
    logger.info(f"Sending current covers to new client {sid}...")
    for p_id, plugin in plugins.items():
        # PC Media
        if hasattr(plugin, '_media_info'):
            cover = plugin._media_info.get('cover')
            title = plugin._media_info.get('title', '')
            if cover:
                logger.info(f" -> Sending PC Media cover ({len(cover)} bytes)")
                socketio.emit(f'plugin_event:{p_id}',
                    {'event': 'cover', 'data': {'cover': cover, 'title': title}},
                    room=sid)
        # Yandex Station
        if hasattr(plugin, 'states'):
            for d_id, state in plugin.states.items():
                cover = state.get('cover', '')
                title = state.get('title', '')
                if cover:
                    logger.info(f" -> Sending Yandex cover for {d_id} ({len(cover)} bytes)")
                    socketio.emit(f'plugin_event:{p_id}',
                        {'event': 'cover', 'data': {'cover': cover, 'device_id': d_id, 'title': title}},
                        room=sid)

def send_lyrics_to_sid(socketio, sid, p_manager):
    """Отправка всех накопленных текстов песен при подключении"""
    from manager import plugins
    plugin = plugins.get("yandex_lyrics")
    if plugin and hasattr(plugin, "_lyrics_cache"):
        logger.info(f"Sending cached lyrics to new client {sid}...")
        for d_id, data in plugin._lyrics_cache.items():
            if data:
                logger.info(f" -> Sending cached lyrics for {d_id}")
                socketio.emit('plugin_event:yandex_lyrics',
                    {'event': 'lyrics', 'data': {'device_id': d_id, 'data': data}},
                    room=sid)
