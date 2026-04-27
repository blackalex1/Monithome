import os
import sys
import importlib
import time
import socket
import ctypes
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

app = Flask(__name__)
app.config['SECRET_KEY'] = 'secret!'
CORS(app)


# Используем threading для максимальной стабильности на Windows
socketio = SocketIO(app, cors_allowed_origins="*", ping_timeout=60, ping_interval=10, async_mode='threading')

# Динамическая загрузка плагинов
plugins = {}

def load_plugins():
    for item in os.listdir(PLUGINS_DIR):
        item_path = os.path.join(PLUGINS_DIR, item)
        if os.path.isdir(item_path) and not item.startswith("__"):
            try:
                spec = importlib.util.spec_from_file_location(
                    f"plugins.{item}.logic", 
                    os.path.join(item_path, "logic.py")
                )
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                
                config_path = os.path.join(item_path, "config.json")
                if os.path.exists(config_path):
                    import json
                    with open(config_path, "r", encoding="utf-8") as f:
                        config = json.load(f)
                
                # Инициализируем плагин
                plugins[item] = module.Plugin(socketio, config, manager=None)
                print(f"Loaded plugin: {item}")
            except Exception as e:
                print(f"Failed to load plugin {item}: {e}")

@socketio.on('connect')
def handle_connect():
    print(f"Client {request.sid} connected")
    ui_config = []
    for p_id, p_instance in plugins.items():
        if hasattr(p_instance, 'config') and p_instance.config:
            ui_config.append(p_instance.config)
    
    emit('ui_config', ui_config)
    
    for p_id, p_instance in plugins.items():
        if hasattr(p_instance, 'get_stats'):
            try:
                emit('stats', p_instance.get_stats())
            except: pass

@socketio.on('command')
def handle_command(data):
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
    except:
        print("Could not determine local IPs, please check your network.")

    socketio.run(app, host='0.0.0.0', port=5000, debug=False, allow_unsafe_werkzeug=True)
