import logging
import threading
import msgpack
import time
import copy
import psutil
import platform
import os
from typing import Dict, List, Any, Optional, Set, Callable

from plugin_manager import load_plugins, discovered_plugins
from config import get_master_config

logger = logging.getLogger("CORE")

# Глобальные переменные для активных инстансов плагинов
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
        self._lock = threading.Lock()
        
        # Очередь на рассылку изменений (батчинг)
        self._dirty_plugins = set()
        
        # Флаг для остановки фоновых потоков
        self._running = True
        
        # Запуск фоновых циклов
        self._broadcast_thread = threading.Thread(target=self._broadcast_loop, daemon=True)
        self._broadcast_thread.start()
        
        self._hal_thread = threading.Thread(target=self._hal_loop, daemon=True)
        self._hal_thread.start()

    def log(self, plugin_name: str, message: str, level: str = "info"):
        """Централизованное логирование"""
        lvl = getattr(logging, level.upper(), logging.INFO)
        logger.log(lvl, f"[{plugin_name.upper()}] {message}")

    def update_plugin_state(self, p_id: str, data: Any) -> None:
        """Обновление состояния плагина и пометка на рассылку"""
        with self._lock:
            if p_id not in self._state:
                self._state[p_id] = {}
            
            # Мерджим данные
            if isinstance(data, dict):
                self._state[p_id].update(data)
            else:
                self._state[p_id] = data
            
            self._dirty_plugins.add(p_id)

    def get_plugin_state(self, p_id: str) -> Any:
        """Получение текущего состояния плагина"""
        with self._lock:
            return copy.deepcopy(self._state.get(p_id, {}))

    def get_all_stats(self) -> Dict[str, Any]:
        """Сбор статистики со всех активных плагинов для начальной загрузки"""
        all_stats = {}
        # 1. Собираем HAL данные (системные)
        if "sys" in self._state:
            all_stats["sys"] = self._state["sys"]
        
        # 2. Опрашиваем каждый плагин
        for p_id, p_instance in self.plugins.items():
            try:
                if hasattr(p_instance, 'get_stats'):
                    all_stats[p_id] = p_instance.get_stats()
                elif p_id in self._state:
                    all_stats[p_id] = self._state[p_id]
            except Exception as e:
                self.log("CORE", f"Error getting stats from {p_id}: {e}", level="error")
        
        return all_stats

    def get_all_initial_events(self) -> List[Dict[str, Any]]:
        """Сбор всех начальных событий от всех плагинов (обложки, тексты и т.д.)"""
        all_events = []
        for p_id, plugin in self.plugins.items():
            try:
                if hasattr(plugin, 'get_initial_events'):
                    events = plugin.get_initial_events()
                    if events:
                        # Оборачиваем события в формат plugin_event
                        for e in events:
                            all_events.append({
                                "plugin_id": p_id,
                                "event": e.get("event"),
                                "data": e.get("data")
                            })
            except Exception as e:
                self.log("CORE", f"Error getting initial events from {p_id}: {e}", level="error")
        return all_events

    def subscribe(self, event_name: str, callback: Callable):
        """Подписка на внутренние события ядра"""
        if event_name not in self._subscribers:
            self._subscribers[event_name] = []
        if callback not in self._subscribers[event_name]:
            self._subscribers[event_name].append(callback)

    def unsubscribe(self, event_name: str, callback: Callable):
        """Отписка от внутренних событий ядра"""
        if event_name in self._subscribers:
            if callback in self._subscribers[event_name]:
                self._subscribers[event_name].remove(callback)

    def emit_event(self, event_name: str, data: Any):
        """Рассылка внутреннего события всем подписчикам"""
        if event_name in self._subscribers:
            for callback in self._subscribers[event_name]:
                try:
                    callback(data)
                except Exception as e:
                    self.log("CORE", f"Error in subscriber {callback}: {e}", level="error")

    def _broadcast_loop(self):
        """Фоновый цикл рассылки изменений клиентам (батчинг)"""
        while self._running:
            time.sleep(0.1) # 100ms батчинг
            
            updates = {}
            with self._lock:
                if self._dirty_plugins:
                    for p_id in self._dirty_plugins:
                        # Если плагин умеет отдавать расширенную статистику - берем её
                        if p_id in self.plugins and hasattr(self.plugins[p_id], 'get_stats'):
                            updates[p_id] = self.plugins[p_id].get_stats()
                        else:
                            updates[p_id] = self._state.get(p_id, {})
                    
                    # Локализация и перевод (если нужно)
                    # lang = get_master_config().get("language", "ru")
                    # updates = self._translate_recursive(updates, p_id, ...)
                    
                    payload = {
                        "stats": updates,
                        "_server_time": time.time()
                    }
                    self._dirty_plugins.clear()
                if updates:

                    try:
                        # Упаковываем в MessagePack (бинарный формат)
                        binary_payload = msgpack.packb(payload, use_bin_type=True)
                        self.socketio.emit('stats', binary_payload, room='authorized', namespace='/')
                        
                        # ДЛЯ ОТЛАДКИ: Шлем также JSON
                        self.socketio.emit('stats_json', payload, room='authorized', namespace='/')
                    except Exception as e:
                        self.log("CORE", f"Broadcast error (MessagePack): {e}", level="error")
                        # Fallback на обычный JSON если MessagePack сбоит
                        try:
                            self.socketio.emit('stats_json', payload, room='authorized', namespace='/')
                        except: pass

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

    def broadcast_ui(self, target_sid=None):
        """Рассылка актуальной конфигурации UI всем клиентам"""
        master = get_master_config()
        # Получаем актуальный список плагинов со всеми переводами
        all_plugins = self.get_all_plugins_info()
        
        payload = {
            "plugins": all_plugins,
            "master": master,
            "_v": master.get("_v", 0)
        }
        
        room = target_sid if target_sid else 'authorized'
        self.socketio.emit('ui_config', payload, room=room, namespace='/')
        self.log("CORE", f"Broadcasted UI config to {room}")

    def start_plugin(self, p_id: str):
        """Динамический запуск плагина"""
        if p_id in self.discovered and p_id not in self.plugins:
            from plugin_manager import instantiate_plugin
            try:
                p_instance = instantiate_plugin(p_id, self)
                if p_instance:
                    self.plugins[p_id] = p_instance
                    if hasattr(p_instance, 'start'):
                        p_instance.start()
                    self.log("CORE", f"Plugin started dynamically: {p_id}")
                    self.broadcast_ui()
            except Exception as e:
                self.log("CORE", f"Failed to start plugin {p_id}: {e}", level="error")

    def stop_plugin(self, p_id: str):
        """Остановка плагина"""
        if p_id in self.plugins:
            p_instance = self.plugins.pop(p_id)
            if hasattr(p_instance, 'stop'):
                p_instance.stop()
            
            # Полностью очищаем состояние плагина, чтобы он исчез с UI
            with self._lock:
                if p_id in self._state:
                    del self._state[p_id]
            
            # Оповещаем клиентов об остановке
            self.socketio.emit('plugin_stopped', {'id': p_id}, room='authorized', namespace='/')
            
            self.log("CORE", f"Plugin stopped: {p_id}")
            self.broadcast_ui()

    def reload_plugin(self, p_id: str):
        """Перезагрузка плагина (остановка + запуск)"""
        if p_id in self.plugins:
            self.log("CORE", f"Reloading plugin: {p_id}")
            self.stop_plugin(p_id)
            
            from plugin_manager import instantiate_plugin
            p_instance = instantiate_plugin(p_id, self)
            if p_instance:
                self.plugins[p_id] = p_instance
                if hasattr(p_instance, 'start'):
                    p_instance.start()
                self.log("CORE", f"Plugin {p_id} reloaded successfully")
                self.broadcast_ui()

    def update_plugin_config_cache(self, p_id: str, new_config: Dict[str, Any]):
        """Обновление кэша конфига в discovered_plugins"""
        if p_id in self.discovered:
            self.discovered[p_id]["config"] = new_config

    def get_all_plugins_info(self) -> List[Dict[str, Any]]:
        """Сбор информации о всех плагинах для UI (активных и нет)"""
        master = get_master_config()
        active_ids = master.get("active_plugins", [])
        from i18n import i18n_engine
        
        # Определяем порядок отображения: сначала согласно plugin_order (или active_plugins как резерв)
        order = master.get("plugin_order") or master.get("active_plugins", [])
        all_ids = list(self.discovered.keys())
        
        # Сначала добавляем те, что есть в списке порядка, затем все остальные (новые)
        sorted_ids = [p_id for p_id in order if p_id in all_ids]
        sorted_ids += [p_id for p_id in all_ids if p_id not in sorted_ids]
        
        info = []
        for p_id in sorted_ids:
            p_data = self.discovered[p_id]
            plugin = self.plugins.get(p_id)
            p_dir = os.path.dirname(p_data.get('path', ''))
            
            if plugin:
                # 1. Если плагин запущен, берем его актуальные (локализованные) данные
                try:
                    item = plugin.get_metadata()
                except Exception as e:
                    self.log(p_id, f"Error getting metadata: {e}", "error")
                    item = p_data.copy()
            else:
                # 2. Если плагин не запущен, берем из обнаруженных и переводим вручную
                item = p_data.copy()
                cfg = item.get("config", {})
                item["name"] = i18n_engine.get_string(p_id, "plugin_name", p_dir, item.get("name"))
                item["description"] = i18n_engine.get_string(p_id, "plugin_description", p_dir, item.get("description"))
                item["version"] = cfg.get("version", "1.0.0")
                item["author_name"] = cfg.get("author_name")
                item["author"] = cfg.get("author") or cfg.get("author_url")
                item["dependencies"] = cfg.get("dependencies", [])
                item["config"] = cfg
            
            # Применяем рекурсивный перевод для всех вложенных структур (если есть)
            item = self._translate_recursive(item, p_id, p_dir)
            
            # Развертываем UI-критичные поля из конфига в корень (для совместимости с Android и браузером)
            cfg = item.get("config", {})
            for key in ["type", "widgets", "actions", "version", "author_name", "author"]:
                if key in cfg:
                    item[key] = cfg[key]
            
            # Убеждаемся, что id и статус активности верные
            item["id"] = p_id
            item["active"] = p_id in active_ids
            
            # Удаляем несериализуемые объекты (классы, модули и т.д.)
            if "class" in item: del item["class"]
            if "module" in item: del item["module"]
            
            info.append(item)
        return info

    def force_refresh_all(self):
        """Принудительное обновление всех плагинов (например, при смене языка)"""
        for p_id, p_instance in self.plugins.items():
            if hasattr(p_instance, 'get_stats'):
                self.update_plugin_state(p_id, p_instance.get_stats())

    def update_state(self, p_id, data):
        """Метод для плагинов: обновление состояния"""
        self.update_plugin_state(p_id, data)

    def broadcast_event(self, p_id, event, data):
        """Рассылка специфичного события плагина (например, track_changed)"""
        # Шлем только авторизованным
        self.socketio.emit(f'plugin_event:{p_id}', {'event': event, 'data': data}, room='authorized', namespace='/')
        
        # Специальная обработка для обложек и текстов - шлем в stats если это нужно
        # (обычно они шлются отдельно чтобы не раздувать msgpack)
        if event == "track_changed":
            # При смене трека помечаем плагин грязным, чтобы обновить stats
            if p_id in self.plugins and hasattr(self.plugins[p_id], 'get_stats'):
                stats_fragment = self.plugins[p_id].get_stats()
            else:
                stats_fragment = self._state.get(p_id, {})
                
            # Но обычно p_id всегда есть.
            payload = {"stats": stats_fragment, "_server_time": time.time()}
            binary_payload = msgpack.packb(payload, use_bin_type=True)
            self.socketio.emit('stats', binary_payload, room='authorized', namespace='/')

    def emit_to_plugin_ui(self, p_id, event, data, sid=None):
        """Метод для плагинов: отправка события в UI (может быть адресной)"""
        
        # Подготавливаем зашифрованную версию (только для yandex_config)
        encrypted_data = None
        if event == "yandex_config" and data:
            from config import get_master_config
            key = get_master_config().get("encryption_key")
            if key:
                from crypto_utils import CryptoUtils
                import json
                try:
                    raw_data = json.dumps(data)
                    encrypted_data = {"encrypted": CryptoUtils.encrypt(raw_data, key)}
                except Exception as e:
                    self.log("CORE", f"Failed to encrypt sensitive data: {e}", level="error")

        # 1. Отправка конкретному SID
        if sid:
            # Для PC GUI - прямое событие.
            local_payload = data.copy() if isinstance(data, dict) else data
            if event == "wizard_data" and isinstance(local_payload, dict):
                local_payload["plugin_id"] = p_id
            self.socketio.emit(event, local_payload, room=sid, namespace='/')
            
            # Для мобильного приложения - обернутое
            payload = encrypted_data if encrypted_data else data
            self.socketio.emit(f'plugin_event:{p_id}', {'event': event, 'data': payload}, room=sid, namespace='/')
            return

        # 2. Массовая рассылка (Broadcast)
        # В локальный интерфейс ПК (local_ui) - ВСЕГДА ПРЯМОЕ СОБЫТИЕ (как раньше)
        # Добавляем plugin_id для wizard_data для совместимости
        local_payload = data.copy() if isinstance(data, dict) else data
        if event == "wizard_data" and isinstance(local_payload, dict):
            local_payload["plugin_id"] = p_id
            
        self.socketio.emit(event, local_payload, room='local_ui', namespace='/')
        
        # Для удаленных устройств - обернутое и/или зашифрованное
        if event == "yandex_config":
            if encrypted_data:
                self.socketio.emit(f'plugin_event:{p_id}', {'event': event, 'data': encrypted_data}, room='secure_clients', namespace='/')
        else:
            # Для всех остальных событий (включая wizard_data для планшета)
            self.socketio.emit(f'plugin_event:{p_id}', {'event': event, 'data': data}, room='authorized', namespace='/')

    def _translate_recursive(self, data, p_id, plugin_dir):
        """Рекурсивный перевод всех строковых значений в словаре/списке"""
        from i18n import i18n_engine
        if isinstance(data, dict):
            new_dict = {}
            for k, v in data.items():
                # Переводим только определенные поля, которые могут быть ключами
                if k in ["name", "description", "label", "text"] and isinstance(v, str):
                    new_dict[k] = i18n_engine.get_string(p_id, v, plugin_dir, v)
                elif isinstance(v, (dict, list)):
                    new_dict[k] = self._translate_recursive(v, p_id, plugin_dir)
                else:
                    new_dict[k] = v
            return new_dict
        elif isinstance(data, list):
            return [self._translate_recursive(item, p_id, plugin_dir) for item in data]
        return data

def initialize_plugins(socketio, manager):
    """Первичная загрузка активных плагинов"""
    from plugin_manager import instantiate_plugin
    master = get_master_config()
    active_ids = master.get("active_plugins", [])
    
    manager.log("CORE", f"Initializing ACTIVE plugins only: {active_ids}")
    
    for p_id in active_ids:
        if p_id in manager.discovered:
            try:
                p_instance = instantiate_plugin(p_id, manager)
                if p_instance:
                    manager.plugins[p_id] = p_instance
                    manager.log("CORE", f"Successfully loaded active plugin: {p_id}")
            except Exception as e:
                manager.log("CORE", f"Failed to load plugin {p_id}: {e}", level="error")
    
    manager.log("CORE", f"Total active plugins running: {len(manager.plugins)}")
    
    # Запуск всех загруженных плагинов
    manager.log("MANAGER", "Starting all plugins...")
    for p_id, p_instance in manager.plugins.items():
        if hasattr(p_instance, 'start'):
            p_instance.start()
