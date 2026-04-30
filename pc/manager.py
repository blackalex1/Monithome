import logging
import threading
from plugin_manager import load_plugins
from config import get_master_config

logger = logging.getLogger("CORE")

# Глобальные переменные для плагинов
discovered_plugins = load_plugins()
plugins = {}

import msgpack
import time
import copy
import psutil
import platform
import logging
import threading

class PluginManager:
    """Core Engine: Единая точка управления состоянием, событиями и HAL"""
    def __init__(self, socketio_instance):
        global plugins
        self.socketio = socketio_instance
        self.plugins = plugins 
        self._subscribers = {} 
        
        # Централизованное состояние
        self._state = {}
        self._dirty_plugins = set()
        self._lock = threading.Lock()
        
        # Мониторинг здоровья плагинов
        self._health = {} # p_id -> last_update_time
        
        # Запуск системных циклов
        threading.Thread(target=self._broadcast_loop, daemon=True).start()
        threading.Thread(target=self._hal_loop, daemon=True).start()
        threading.Thread(target=self._health_check_loop, daemon=True).start()

    def update_plugin_state(self, p_id, data):
        """Обновление состояния плагина и пометка на рассылку"""
        with self._lock:
            if p_id not in self._state:
                self._state[p_id] = {}
            
            # Мерджим данные
            if isinstance(data, dict):
                self._state[p_id].update(data)
            else:
                self._state[p_id] = data
            
            self._health[p_id] = time.time()
            self._dirty_plugins.add(p_id)

    def _broadcast_loop(self):
        """Фоновый цикл для батчинга обновлений (раз в 100мс)"""
        while True:
            time.sleep(0.1)
            if self._dirty_plugins:
                with self._lock:
                    updates = {p_id: self._state[p_id] for p_id in self._dirty_plugins}
                    payload = {
                        "stats": updates,
                        "_server_time": time.time()
                    }
                    self._dirty_plugins.clear()
                
                # Упаковываем в MessagePack (бинарный формат)
                binary_payload = msgpack.packb(payload, use_bin_type=True)
                self.socketio.emit('stats', binary_payload, to='authorized')

    def _hal_loop(self):
        """HAL по требованию: опрашиваем только если есть подписчики"""
        while True:
            try:
                # Опрашиваем HAL только если есть хоть один плагин, которому это нужно
                if self._subscribers.get("hal_update"):
                    cpu = psutil.cpu_percent(interval=None)
                    ram = psutil.virtual_memory()
                    
                    hal_data = {
                        "cpu_load": cpu,
                        "ram_percent": ram.percent,
                        "ram_used_gb": round(ram.used / (1024**3), 2),
                        "ram_total_gb": round(ram.total / (1024**3), 2),
                        "hal_timestamp": time.time()
                    }
                    
                    self.update_plugin_state("sys", hal_data)
                    self.emit_event("hal_update", hal_data)
            except Exception as e:
                self.log("HAL", f"Error: {e}", level="error")
            time.sleep(1)

    def _health_check_loop(self):
        """Проверка «зависших» плагинов"""
        while True:
            time.sleep(10)
            now = time.time()
            with self._lock:
                for p_id, last_seen in list(self._health.items()):
                    if now - last_seen > 30: # Если плагин молчит больше 30 секунд
                        # Помечаем в стейте как Offline (опционально)
                        if p_id in self._state and isinstance(self._state[p_id], dict):
                            if self._state[p_id].get("status") != "stale":
                                self._state[p_id]["status"] = "stale"
                                self._dirty_plugins.add(p_id)

    def start_plugin(self, p_id):
        """Запуск плагина, если он еще не запущен"""
        if p_id in self.plugins: return True
        
        from .plugin_manager import load_plugins
        discovered = load_plugins()
        if p_id in discovered:
            try:
                p_data = discovered[p_id]
                instance = p_data["class"](self.socketio, p_data["config"], self)
                self.plugins[p_id] = instance
                if hasattr(instance, 'start'):
                    threading.Thread(target=instance.start, daemon=True).start()
                self.log("CORE", f"Plugin {p_id} started dynamically")
                return True
            except Exception as e:
                self.log("CORE", f"Failed to start {p_id}: {e}", level="error")
        return False

    def stop_plugin(self, p_id):
        """Остановка и удаление плагина из памяти"""
        if p_id in self.plugins:
            try:
                instance = self.plugins[p_id]
                if hasattr(instance, 'stop'):
                    instance.stop()
                del self.plugins[p_id]
                # Убираем из стейта, чтобы не слать старые данные
                with self._lock:
                    if p_id in self._state: del self._state[p_id]
                    if p_id in self._dirty_plugins: self._dirty_plugins.remove(p_id)
                self.log("CORE", f"Plugin {p_id} stopped and unloaded")
                return True
            except Exception as e:
                self.log("CORE", f"Failed to stop {p_id}: {e}", level="error")
        return False

    def reload_plugin(self, p_id):
        """Динамическая перезагрузка плагина"""
        self.log("CORE", f"Reloading plugin: {p_id}...")
        self.stop_plugin(p_id)
        return self.start_plugin(p_id)

    def subscribe(self, event_name, callback):
        if event_name not in self._subscribers:
            self._subscribers[event_name] = []
        self._subscribers[event_name].append(callback)

    def emit_event(self, event_name, data=None):
        if event_name in self._subscribers:
            for callback in self._subscribers[event_name]:
                try:
                    callback(data)
                except Exception as e:
                    self.log("CORE", f"Error in event handler {event_name}: {e}", level="error")

    def get_all_stats(self):
        """Сбор текущего состояния для начального рукопожатия"""
        with self._lock:
            return copy.deepcopy(self._state)

    def broadcast_stats(self, stats_fragment):
        """Совместимость с Модульностью 3.0: прокси на новое ядро"""
        p_id = None
        if isinstance(stats_fragment, dict):
            p_id = stats_fragment.get("plugin_id")
            # Если это старый формат {plugin_id: ..., data: ...}
            data = stats_fragment.get("stats") or stats_fragment
            if p_id and "plugin_id" in data:
                # Очищаем от метаданных если они внутри
                data = {k:v for k,v in data.items() if k != "plugin_id"}
        
        if p_id:
            self.update_plugin_state(p_id, stats_fragment)
        else:
            # Если ID не найден, кидаем как есть ( fallback ) в бинарном виде
            payload = {"stats": stats_fragment, "_server_time": time.time()}
            binary_payload = msgpack.packb(payload, use_bin_type=True)
            self.socketio.emit('stats', binary_payload, to='authorized')

    def broadcast_ui(self):
        """Уведомление всех клиентов об изменении структуры UI"""
        try:
            master = get_master_config()
            active_order = master.get("active_plugins", [])
            ui_config = []
            
            for p_id in active_order:
                if p_id in self.plugins:
                    p_instance = self.plugins[p_id]
                    if hasattr(p_instance, 'config'):
                        ui_config.append(p_instance.config.copy())
            
            data = {
                "language": master.get("language", "ru"),
                "config": ui_config
            }
            
            self.socketio.emit('ui_config', data, to='authorized')
            # Также обновляем метаданные в менеджере
            self.socketio.emit('manager_data', {
                'master_config': master,
                'all_plugins': self.get_all_plugins_info_internal()
            }, to='authorized')
            
            self.log("CORE", "UI Configuration broadcasted successfully")
        except Exception as e:
            self.log("CORE", f"Failed to broadcast UI config: {e}", level="error")

    def get_all_plugins_info_internal(self):
        """Внутренний метод для получения инфо о плагинах"""
        info = []
        master = get_master_config()
        active_list = master.get("active_plugins", [])
        for p_id, p in self.plugins.items():
            if hasattr(p, 'config'):
                p_info = {'id': p_id, 'active': p_id in active_list, 'config': p.config}
                if isinstance(p.config, dict):
                    for k, v in p.config.items(): p_info[k] = v
                info.append(p_info)
        return info

    def start(self):
        self.log("Manager", "Starting all plugins...")
        for plugin in self.plugins.values():
            if hasattr(plugin, 'start'):
                threading.Thread(target=plugin.start, daemon=True).start()

    def log(self, plugin_name, message, level="info"):
        p_logger = logging.getLogger(plugin_name.upper())
        if level == "info": p_logger.info(message)
        elif level == "warning": p_logger.warning(message)
        elif level == "error": p_logger.error(message)
        elif level == "debug": p_logger.debug(message)

    def emit_to_plugin_ui(self, plugin_id, event, data):
        self.socketio.emit(f"plugin_event:{plugin_id}", {"event": event, "data": data}, to='authorized')

def initialize_plugins(socketio, p_manager):
    global plugins
    master = get_master_config()
    active_list = master.get("active_plugins", [])
    
    logger.info(f"Initializing ACTIVE plugins only: {active_list}")
    for p_id in active_list:
        if p_id in discovered_plugins:
            try:
                p_data = discovered_plugins[p_id]
                instance = p_data["class"](socketio, p_data["config"], p_manager)
                plugins[p_id] = instance
                
                if hasattr(instance, 'get_stats'):
                    p_manager.update_plugin_state(p_id, instance.get_stats())
                    
                logger.info(f"Successfully loaded active plugin: {p_id}")
            except Exception as e:
                logger.error(f"Failed to initialize plugin {p_id}: {e}")
    
    logger.info(f"Total active plugins running: {len(plugins)}")
    p_manager.start()

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
                if 'author_url' in p.config and 'author' not in p_info:
                    p_info['author'] = p.config['author_url']
            info.append(p_info)
    return info

