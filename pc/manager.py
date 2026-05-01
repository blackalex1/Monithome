import logging
import threading
import msgpack
import time
import copy
import psutil
import platform
import os

from plugin_manager import load_plugins
from config import get_master_config

logger = logging.getLogger("CORE")

# Глобальные переменные для плагинов
discovered_plugins = load_plugins()
plugins = {}

class PluginManager:
    """Core Engine: Единая точка управления состоянием, событиями и HAL"""
    def __init__(self, socketio_instance):
        global plugins, discovered_plugins
        self.socketio = socketio_instance
        self.plugins = plugins
        self.discovered = discovered_plugins
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

    def is_plugin_active(self, p_id):
        """Проверка, запущен ли плагин"""
        return p_id in self.plugins

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
        # Собираем пути к плагинам для перевода
        plugin_dirs = {}
        for p_id, p_data in self.discovered.items():
            plugin_dirs[p_id] = p_data.get("path")

        while True:
            time.sleep(0.1)
            if self._dirty_plugins:
                master = get_master_config()
                lang = master.get("language", "ru")
                
                with self._lock:
                    updates = {}
                    for p_id in list(self._dirty_plugins):
                        data = self._state[p_id]
                        p_dir = plugin_dirs.get(p_id)
                        if p_dir:
                            # Переводим данные перед отправкой
                            translated = self._translate_recursive(data, p_id, p_dir, lang)
                            updates[p_id] = translated
                        else:
                            updates[p_id] = data
                    
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

    def force_refresh_all(self):
        """Принудительное обновление состояния всех плагинов (напр. при смене языка)"""
        self.log("CORE", "Forcing refresh of all plugins state...")
        for p_id, instance in self.plugins.items():
            # 1. Пробуем вызвать специфические методы обновления
            updated = False
            for method_name in ['_update_disks_state', '_update_stats', 'refresh', 'update']:
                if hasattr(instance, method_name):
                    try:
                        method = getattr(instance, method_name)
                        if callable(method):
                            method()
                            updated = True
                            break 
                    except: pass
            
            # 2. Если специфических методов нет, или просто для надежности - дергаем get_stats
            if not updated and hasattr(instance, 'get_stats'):
                try:
                    stats = instance.get_stats()
                    if stats:
                        self.update_plugin_state(p_id, stats)
                except: pass

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
            master = get_master_config()
            lang = master.get("language", "ru")
            # Мы не знаем p_id здесь, поэтому переводим по глобальному конфигу (если получится)
            # Но обычно p_id всегда есть.
            payload = {"stats": stats_fragment, "_server_time": time.time()}
            binary_payload = msgpack.packb(payload, use_bin_type=True)
            self.socketio.emit('stats', binary_payload, to='authorized')

    def _translate_recursive(self, data, p_id, plugin_dir, lang):
        """Рекурсивный перевод всех строковых значений в словаре/списке"""
        from i18n import i18n_engine
        if isinstance(data, dict):
            new_dict = {}
            for k, v in data.items():
                if isinstance(v, (dict, list)):
                    new_dict[k] = self._translate_recursive(v, p_id, plugin_dir, lang)
                elif isinstance(v, str) and (k.endswith("label") or k.endswith("text") or k == "title" or k == "description" or k == "name" or k == "lyrics"):
                    # Пытаемся перевести, если это похоже на ключ или содержит текст
                    translated = i18n_engine.get_string(p_id, v, plugin_dir, v, lang=lang)
                    new_dict[k] = translated
                else:
                    new_dict[k] = v
            return new_dict
        elif isinstance(data, list):
            return [self._translate_recursive(item, p_id, plugin_dir, lang) for item in data]
        return data

    def broadcast_ui(self, target_sid=None):
        """Рассылка конфигурации интерфейса всем авторизованным клиентам"""
        try:
            master = get_master_config()
            active_order = master.get("active_plugins", [])
            
            # Получаем актуальную информацию обо всех плагинах
            all_plugins_info = self.get_all_plugins_info()
            
            target = target_sid if target_sid else 'authorized'
            self.socketio.emit('manager_data', {
                'master_config': master,
                'all_plugins': all_plugins_info
            }, to=target)
            
            self.log("CORE", f"UI Config sent to {target}. Order: {active_order}")
        except Exception as e:
            self.log("CORE", f"Failed to broadcast UI config: {e}", level="error")

    def get_all_plugins_info(self):
        """Сбор информации обо всех обнаруженных плагинах для UI менеджера"""
        from i18n import i18n_engine
        from plugin_manager import load_plugins
        discovered = load_plugins()
        master = get_master_config()
        active_list = master.get("active_plugins", [])
        lang = master.get("language", "ru")
        
        info_map = {}
        # 1. Сначала собираем данные о всех плагинах в мапу
        for p_id, p_data in discovered.items():
            config = p_data.get("config", {})
            plugin_dir = p_data.get("path")
            
            p_info = {
                "id": p_id,
                "name": config.get("name"),
                "description": config.get("description"),
                "name_en": config.get("name"),
                "description_en": config.get("description"),
                "version": config.get("version", "1.0.0"),
                "author": config.get("author_name", "Unknown"),
                "author_url": config.get("author_url"),
                "active": p_id in self.plugins,
                "needs_hal": p_id in self._subscribers.get("hal_update", [])
            }
            
            # Если плагин запущен, берем его живой конфиг (может перетереть поля выше)
            if p_id in self.plugins:
                p_instance = self.plugins[p_id]
                if hasattr(p_instance, 'config'):
                    if isinstance(p_instance.config, dict):
                        p_info.update(p_instance.config)
            
            # ТЕПЕРЬ РЕКУРСИВНО ПЕРЕВОДИМ ВЕСЬ КОНФИГ
            translated_info = self._translate_recursive(p_info, p_id, plugin_dir, lang)
            # Отдельно для английского названия (для менеджера)
            translated_info["name_en"] = i18n_engine.get_string(p_id, p_info.get("name"), plugin_dir, p_info.get("name"), lang="en")
            
            info_map[p_id] = translated_info

        # 2. Формируем финальный список согласно порядку в active_list
        result = []
        # Сначала активные в нужном порядке
        for p_id in active_list:
            if p_id in info_map:
                result.append(info_map[p_id])
        
        # Затем все остальные, которых нет в active_list
        for p_id in info_map:
            if p_id not in active_list:
                result.append(info_map[p_id])
                
        return result

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
