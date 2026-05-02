import logging
import socket
from flask import Flask
from flask_socketio import SocketIO

import psutil
import sys
import ctypes
from manager import PluginManager, initialize_plugins
from plugin_manager import discovered_plugins
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
        config = p_data.get('config', {})
        req_admin = config.get('requires_admin')
        
        # 1. Жесткое требование (requires_admin: true)
        if req_admin is True or str(req_admin).lower() == 'true':
            logger.info(f"Plugin '{p_id}' ALWAYS requires admin privileges.")
            return True
            
        # 2. Динамическое требование (requires_admin: "if_required")
        if req_admin == "if_required":
            logger.info(f"Plugin '{p_id}' has conditional admin requirements. Checking...")
            try:
                module = p_data.get('module')
                if module and hasattr(module, 'check_requirements'):
                    if not module.check_requirements():
                        logger.info(f"Plugin '{p_id}' reports that ADMIN privileges ARE needed now.")
                        return True
                    else:
                        logger.info(f"Plugin '{p_id}' can run WITHOUT admin right now.")
            except Exception as e:
                logger.error(f"Error checking requirements for '{p_id}': {e}")
                
    logger.info("No active admin requirements found.")
    return False

import os
import sys

def get_resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    base_path = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base_path, relative_path)

# Настройка логирования
logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s [%(name)s] %(message)s', datefmt='%H:%M:%S')
logger = logging.getLogger("CORE")

# Определяем пути к GUI
dist_path = get_resource_path('../pc_gui/dist')
if not os.path.exists(dist_path):
    # Если запущен из pc/, пробуем найти в корне
    dist_path = get_resource_path('dist')

app = Flask(__name__, 
            template_folder=dist_path,
            static_folder=os.path.join(dist_path, 'assets'),
            static_url_path='/assets')

socketio = SocketIO(app, cors_allowed_origins="*", async_mode='threading')

# --- Интеграция консоли в GUI ---
class SocketLogHandler(logging.Handler):
    def __init__(self, sio):
        super().__init__()
        self.sio = sio

    def emit(self, record):
        try:
            # Не логируем системные логи самих библиотек сокетов, чтобы избежать рекурсии
            if record.name.startswith('engineio') or record.name.startswith('socketio'):
                return
            
            msg = self.format(record)
            # Избегаем бесконечного цикла логов
            if "system_log" in msg: return
            self.sio.emit('system_log', {
                'message': msg,
                'level': record.levelname,
                'time': record.created
            })
        except:
            pass

log_handler = SocketLogHandler(socketio)
log_handler.setFormatter(logging.Formatter('%(levelname)s [%(name)s] %(message)s'))
logging.getLogger().addHandler(log_handler)

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
