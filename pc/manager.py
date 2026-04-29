import logging
import threading
from plugin_manager import load_plugins
from config import get_master_config

logger = logging.getLogger("CORE")

# Глобальные переменные для плагинов
discovered_plugins = load_plugins()
plugins = {}

class PluginManager:
    """Единая точка входа для общения плагинов с сервером и друг с другом"""
    def __init__(self, socketio_instance):
        self.socketio = socketio_instance

    def get_plugin(self, p_id):
        global plugins
        return plugins.get(p_id)

    def broadcast_stats(self, stats):
        """Безопасная рассылка статистики только авторизованным устройствам"""
        self.socketio.emit('stats', stats, to='authorized')

    def broadcast_ui(self):
        """Уведомление всех клиентов об изменении структуры UI"""
        from sockets import send_ui_config
        send_ui_config(self.socketio, broadcast=True)

    def log(self, plugin_name, message, level="info"):
        """Единое логирование для всех плагинов"""
        p_logger = logging.getLogger(plugin_name.upper())
        if level == "info": p_logger.info(message)
        elif level == "warning": p_logger.warning(message)
        elif level == "error": p_logger.error(message)
        elif level == "debug": p_logger.debug(message)

    def emit_to_plugin_ui(self, plugin_id, event, data):
        """Отправка специфических данных конкретному плагину в UI"""
        self.socketio.emit(f"plugin_event:{plugin_id}", {"event": event, "data": data}, to='authorized')

def initialize_plugins(socketio, p_manager):
    """Инициализация всех обнаруженных плагинов"""
    global plugins
    logger.info(f"Starting initialization of {len(discovered_plugins)} discovered plugins...")
    for p_id, p_data in discovered_plugins.items():
        try:
            plugins[p_id] = p_data["class"](socketio, p_data["config"], p_manager)
            logger.info(f"Successfully loaded plugin: {p_id}")
        except Exception as e:
            logger.error(f"CRITICAL: Failed to initialize plugin {p_id}: {e}")
            import traceback
            logger.error(traceback.format_exc())
    logger.info(f"Total active plugins: {len(plugins)}")

def get_all_plugins_info():
    info = []
    master = get_master_config()
    active_list = master.get("active_plugins", [])
    
    for p_id, p in plugins.items():
        if hasattr(p, 'config'):
            p_info = {
                'id': p_id,
                'active': p_id in active_list,
                'config': p.config
            }
            if isinstance(p.config, dict):
                for k, v in p.config.items():
                    p_info[k] = v
                
                # Совместимость с InfoModal: мапим ссылки
                if 'author_url' in p.config and 'author' not in p_info:
                    p_info['author'] = p.config['author_url']
            
            info.append(p_info)
    
    logger.info(f"Reporting {len(info)} plugins to client")
    return info
