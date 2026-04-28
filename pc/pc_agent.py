import os
import sys
import importlib
import time
import socket
import ctypes
import json
from flask import Flask, render_template, request
from flask_socketio import SocketIO, emit
from flask_cors import CORS

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

# Настройки
PLUGINS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plugins")
# ---------------------

app = Flask(__name__, 
            static_folder='../pc_gui/dist', 
            template_folder='../pc_gui/dist',
            static_url_path='')
app.config['SECRET_KEY'] = 'secret!'
CORS(app)

@app.route('/')
def index():
    # Отключаем кеширование для главной страницы, чтобы браузер всегда подхватывал новые JS/CSS после сборки
    response = render_template('index.html')
    from flask import make_response
    res = make_response(response)
    res.headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    return res


# Используем threading для максимальной стабильности на Windows
socketio = SocketIO(app, cors_allowed_origins="*", ping_timeout=60, ping_interval=10, async_mode='threading')

# Загрузка главного конфига
MASTER_CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "master_config.json")

def get_master_config():
    if os.path.exists(MASTER_CONFIG_PATH):
        with open(MASTER_CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"active_plugins": [], "plugin_settings": {}}

def save_master_config(config):
    with open(MASTER_CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)

# Динамическая загрузка плагинов
plugins = {}

def load_plugins(target_id=None):
    global plugins
    
    master = get_master_config()
    active_ids = master.get("active_plugins", [])
    
    # Если указан target_id, перезагружаем только его
    if target_id and target_id in plugins:
        print(f"[CORE] Reloading single plugin: {target_id}")
        p_path = os.path.join(PLUGINS_DIR, target_id)
        if os.path.exists(p_path):
            try:
                # Останавливаем старый экземпляр
                if hasattr(plugins[target_id], 'stop'):
                    try: plugins[target_id].stop()
                    except: pass
                
                # Загружаем заново
                spec = importlib.util.spec_from_file_location(f"plugins.{target_id}.logic", os.path.join(p_path, "logic.py"))
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                
                with open(os.path.join(p_path, "config.json"), "r", encoding="utf-8") as f:
                    p_config = json.load(f)
                
                plugins[target_id] = module.Plugin(socketio, p_config, manager=None)
                print(f"[CORE] Plugin {target_id} reloaded successfully.")
                return
            except Exception as e:
                print(f"[CORE] Failed to reload {target_id}: {e}")

    # Полная перезагрузка
    print("[CORE] Full plugins reload...")
    for p_id, p_inst in plugins.items():
        if hasattr(p_inst, 'stop'):
            try: p_inst.stop()
            except: pass

    temp_plugins = {}
    if not os.path.exists(PLUGINS_DIR): return

    for item in os.listdir(PLUGINS_DIR):
        item_path = os.path.join(PLUGINS_DIR, item)
        if os.path.isdir(item_path) and not item.startswith("__"):
            try:
                spec = importlib.util.spec_from_file_location(f"plugins.{item}.logic", os.path.join(item_path, "logic.py"))
                if not spec: continue
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                
                config_path = os.path.join(item_path, "config.json")
                p_config = {}
                if os.path.exists(config_path):
                    with open(config_path, "r", encoding="utf-8") as f:
                        p_config = json.load(f)
                
                temp_plugins[item] = module.Plugin(socketio, p_config, manager=None)
                print(f"Loaded plugin: {item}")
            except Exception as e:
                print(f"Failed to load plugin {item}: {e}")

    # Формируем словарь в правильном порядке
    ordered_plugins = {}
    for p_id in active_ids:
        if p_id in temp_plugins:
            ordered_plugins[p_id] = temp_plugins.pop(p_id)
    for p_id, p_inst in temp_plugins.items():
        ordered_plugins[p_id] = p_inst
    
    plugins = ordered_plugins

def send_manager_data(broadcast=False):
    master = get_master_config()
    all_plugins = []
    
    for item in os.listdir(PLUGINS_DIR):
        item_path = os.path.join(PLUGINS_DIR, item)
        if os.path.isdir(item_path) and not item.startswith("__"):
            config_path = os.path.join(item_path, "config.json")
            info = {
                "id": item, 
                "name": item, 
                "active": item in master["active_plugins"],
                "version": None,
                "author": None,
                "author_name": None,
                "description": None,
                "name_en": None,
                "description_en": None
            }
            
            # Читаем конфиг для всех данных
            if os.path.exists(config_path):
                try:
                    with open(config_path, "r", encoding="utf-8") as f:
                        p_cfg = json.load(f)
                        info["name"] = p_cfg.get("name", info["name"])
                        info["config"] = p_cfg
                        info["version"] = p_cfg.get("version", info["version"])
                        # Поддержка новых полей авторства
                        info["author_name"] = p_cfg.get("author_name", info["author_name"])
                        info["author"] = p_cfg.get("author_url", p_cfg.get("author", info["author"]))
                        info["name_en"] = p_cfg.get("name_en", info["name_en"])
                        info["description"] = p_cfg.get("description", info["description"])
                        info["description_en"] = p_cfg.get("description_en", info["description_en"])
                        info["dependencies"] = p_cfg.get("dependencies", [])
                except: pass
            all_plugins.append(info)
    
    data = {
        "master_config": master,
        "all_plugins": all_plugins
    }
    
    if broadcast:
        socketio.emit('manager_data', data)
    else:
        emit('manager_data', data)

@socketio.on('get_manager_data')
def handle_get_manager_data():
    send_manager_data(broadcast=False)

@socketio.on('update_plugin_order')
def handle_update_order(new_order):
    # Обновляем в памяти сразу
    master = get_master_config()
    master["active_plugins"] = new_order
    
    # Сохраняем на диск (это можно делать асинхронно, но пока оставим так)
    save_master_config(master)
    
    # Быстрая пересортировка словаря plugins в памяти
    global plugins
    new_plugins = {}
    for p_id in new_order:
        if p_id in plugins:
            new_plugins[p_id] = plugins[p_id]
    for p_id, p_inst in plugins.items():
        if p_id not in new_plugins:
            new_plugins[p_id] = p_inst
    plugins = new_plugins
    
    # Отправляем обновленный конфиг, используя уже имеющийся в памяти новый порядок
    send_ui_config(broadcast=True, active_order=new_order)
    send_manager_data(broadcast=True)
    print(f"[{time.strftime('%H:%M:%S')}] [ORDER] New order applied: {new_order}")

@socketio.on('save_master_config')
def handle_save_config(data):
    save_master_config(data)
    print("Master config saved, reloading plugins...")
    load_plugins() # Перезагружаем плагины на лету
    send_ui_config(broadcast=True) # Отправляем обновленный ui_config ВСЕМ клиентам
    send_manager_data(broadcast=True) # Синхронизируем все открытые браузеры

@socketio.on('apply_plugin_wizard')
def handle_plugin_wizard(data):
    p_id = data.get('plugin_id')
    selections = data.get('selections', [])
    
    if p_id in plugins:
        p_instance = plugins[p_id]
        if hasattr(p_instance, 'handle_wizard'):
            try:
                # Плагин сам решает, как обработать свой мастер настройки и сохранить config.json
                p_instance.handle_wizard(selections)
                
                # Общие действия после изменения конфига: ПЕРЕЗАГРУЖАЕМ ТОЛЬКО ЭТОТ ПЛАГИН
                load_plugins(target_id=p_id)
                send_ui_config(broadcast=True)
                send_manager_data(broadcast=True)
                print(f"[CORE] Wizard applied for plugin: {p_id} (Partial reload)")
            except Exception as e:
                print(f"[CORE] Error in plugin wizard ({p_id}): {e}")

@socketio.on('save_plugin_config')
def handle_save_plugin_config(data):
    p_id = data.get('id')
    new_cfg = data.get('config')
    if p_id and new_cfg:
        config_path = os.path.join(PLUGINS_DIR, p_id, "config.json")
        try:
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(new_cfg, f, indent=2, ensure_ascii=False)
            print(f"Config for {p_id} saved.")
            load_plugins(target_id=p_id)
            send_ui_config(broadcast=True)
            send_manager_data(broadcast=True)
        except Exception as e:
            print(f"Error saving plugin config: {e}")

@socketio.on('plugin_command')
def handle_plugin_command(data):
    p_id = data.get('plugin_id')
    target = data.get('target', 'all')
    action = data.get('action')
    print(f"[CORE] Plugin command: {p_id} -> {action}")
    if p_id in plugins:
        threading.Thread(target=plugins[p_id].handle_command, args=(target, action), daemon=True).start()

def send_ui_config(broadcast=False, active_order=None):
    if active_order is None:
        master = get_master_config()
        active_order = master.get("active_plugins", [])
        
    ui_config = []
    # Собираем конфиги строго в порядке
    for p_id in active_order:
        if p_id in plugins:
            p_instance = plugins[p_id]
            if hasattr(p_instance, 'config') and p_instance.config:
                # Делаем копию, чтобы React на клиенте точно увидел изменения
                ui_config.append(p_instance.config.copy())
    
    data = {
        "language": master.get("language", "ru"),
        "config": ui_config
    }

    if broadcast:
        socketio.emit('ui_config', data)
    else:
        emit('ui_config', data)

# Хранилище авторизации
authorized_tokens = []
pending_pairings = {} # {sid: {code, device_info}}

@socketio.on('connect')
def handle_connect(auth=None):
    sid = request.sid
    # Проверяем токен (если он передан в auth)
    token = None
    if auth and isinstance(auth, dict):
        token = auth.get('token')
    
    # Менеджер (локальный) всегда авторизован
    is_localhost = request.remote_addr == '127.0.0.1' or request.remote_addr == 'localhost'
    
    if is_localhost:
        print(f"Manager connected: {sid}")
        send_ui_config(broadcast=False)
        for p_id, p_instance in plugins.items():
            if hasattr(p_instance, 'get_stats'):
                try: emit('stats', p_instance.get_stats())
                except: pass
        return True

    # Для внешних устройств (планшетов)
    master = get_master_config()
    trusted = master.get("trusted_tokens", [])
    
    if token in trusted and token is not None:
        print(f"Trusted device connected: {sid}")
        send_ui_config(broadcast=False)
        for p_id, p_instance in plugins.items():
            if hasattr(p_instance, 'get_stats'):
                try: emit('stats', p_instance.get_stats())
                except: pass
        return True
    
    # Если не авторизован - инициируем спаривание
    import random
    code = "".join([str(random.randint(0, 9)) for _ in range(6)])
    pending_pairings[sid] = code
    
    print(f"New device pairing request: {sid}, code: {code}")
    # Отправляем код всем менеджерам
    socketio.emit('pairing_request', {'sid': sid, 'code': code}, room=None)
    
    # Планшету говорим, что нужно авторизоваться
    emit('auth_required', {'sid': sid})
    return True

@socketio.on('auth_attempt')
def handle_auth_attempt(data):
    sid = request.sid
    code = data.get('code')
    
    if sid in pending_pairings and pending_pairings[sid] == code:
        import uuid
        new_token = str(uuid.uuid4())
        
        # Сохраняем токен
        master = get_master_config()
        if "trusted_tokens" not in master: master["trusted_tokens"] = []
        master["trusted_tokens"].append(new_token)
        save_master_config(master)
        
        # Удаляем из ожидающих
        del pending_pairings[sid]
        
        # Отправляем токен планшету
        emit('auth_success', {'token': new_token})
        print(f"Device {sid} authorized successfully")
        
        # Уведомляем менеджеров
        socketio.emit('pairing_complete', {'sid': sid})
        
        # Отправляем данные после успеха
        send_ui_config(broadcast=False)
    else:
        emit('auth_failed', {'message': 'Invalid code'})

@socketio.on('cancel_pairing')
def handle_cancel_pairing(data):
    target_sid = data.get('sid')
    if target_sid in pending_pairings:
        del pending_pairings[target_sid]
        # Уведомляем само устройство
        socketio.emit('pairing_cancel', room=target_sid)
        print(f"Pairing for {target_sid} cancelled by manager")

@socketio.on('disconnect')
def handle_disconnect():
    sid = request.sid
    if sid in pending_pairings:
        del pending_pairings[sid]
        socketio.emit('pairing_cancel', {'sid': sid})
    print(f"Client {sid} disconnected")

@socketio.on('command')
def handle_command(data):
    # Дополнительная проверка безопасности для команд
    # (В реальном приложении здесь должна быть проверка токена в сессии)
    p_id = data.get('plugin_id')
    target = data.get('target', 'pc')
    action = data.get('action')
    
    print(f"[{time.strftime('%H:%M:%S')}] Incoming command: {p_id} -> {action}")
    
    if p_id in plugins:
        # Запускаем выполнение команды в отдельном потоке, чтобы не блокировать сокет!
        threading.Thread(target=plugins[p_id].handle_command, args=(target, action), daemon=True).start()

def check_admin_requirements():
    for item in os.listdir(PLUGINS_DIR):
        config_path = os.path.join(PLUGINS_DIR, item, "config.json")
        if os.path.exists(config_path):
            try:
                import json
                with open(config_path, "r", encoding="utf-8") as f:
                    if json.load(f).get("requires_admin", False):
                        return True
            except: pass
    return False

if __name__ == '__main__':
    if check_admin_requirements() and not is_admin():
        print("Плагины требуют права Администратора. Запрашиваю...")
        script = os.path.abspath(sys.argv[0])
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, f'"{script}"', None, 1)
        sys.exit()

    import threading # Импортируем здесь для безопасности
    load_plugins()
    print("Plugin Host started!")
    
    try:
        hostname = socket.gethostname()
        print(f"Hostname: {hostname}")
        ips = socket.gethostbyname_ex(hostname)[2]
        print("URLS FOR TABLET:")
        for ip in ips:
            print(f"  - http://{ip}:5000")
        
        # Open GUI in default browser
        import webbrowser
        webbrowser.open("http://localhost:5000")
    except:
        print("Could not determine local IPs, please check your network.")

    socketio.run(app, host='0.0.0.0', port=5000, debug=False, allow_unsafe_werkzeug=True)
