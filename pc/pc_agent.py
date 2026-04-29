import logging
import socket
from flask import Flask
from flask_socketio import SocketIO

import psutil
import sys
import ctypes
from manager import PluginManager, initialize_plugins, discovered_plugins
from routes import register_routes
from sockets import register_socket_events

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def check_admin_required():
    logger.info(f"Checking admin requirements for {len(discovered_plugins)} plugins...")
    for p_id, p_data in discovered_plugins.items():
        req = p_data.get('config', {}).get('requires_admin')
        if req:
            logger.info(f"Plugin '{p_id}' REQUIRES admin privileges.")
            return True
    logger.info("No plugins require admin privileges.")
    return False

# Настройка логирования
logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s [%(name)s] %(message)s', datefmt='%H:%M:%S')
logger = logging.getLogger("CORE")

app = Flask(__name__, 
            template_folder='../pc_gui/dist',
            static_folder='../pc_gui/dist/assets',
            static_url_path='/assets')

socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

# Инициализация глобального менеджера
p_manager = PluginManager(socketio)

# Регистрация маршрутов и событий
register_routes(app, p_manager)
register_socket_events(socketio, p_manager)

if __name__ == '__main__':
    # Проверка прав администратора
    if check_admin_required() and not is_admin():
        logger.warning("One or more plugins require ADMIN privileges. Relaunching...")
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, " ".join(sys.argv), None, 1)
        sys.exit(0)

    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)
    
    logger.info("URLS FOR TABLET:")
    for interface, addrs in psutil.net_if_addrs().items():
        for addr in addrs:
            if addr.family == socket.AF_INET:
                logger.info(f"  - http://{addr.address}:5000")
    
    # Запуск плагинов
    initialize_plugins(socketio, p_manager)
    
    # Запуск сервера
    socketio.run(app, host='0.0.0.0', port=5000)
