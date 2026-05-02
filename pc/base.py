import logging
import threading
from typing import Dict, List, Any, Optional

class BasePlugin:
    """Базовый класс для всех плагинов"""
    def __init__(self, socketio: Any, config: Dict[str, Any], manager: Any):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._lock = threading.Lock()
        # ID плагина берется из конфига или имени класса
        self.p_id = config.get('id') if isinstance(config, dict) else None
        if not self.p_id:
            # Пытаемся извлечь id из пути модуля (напр. plugins.pc_system.logic -> pc_system)
            parts = self.__class__.__module__.split('.')
            if len(parts) >= 2:
                self.p_id = parts[-2]
            else:
                self.p_id = self.__class__.__name__.lower()

    def update_state(self, data):
        """Отправка данных в ядро для последующей рассылки"""
        self.manager.update_plugin_state(self.p_id, data)

    def get_stats(self):
        """Метод для начального получения данных"""
        return {}

    def start(self):
        """Метод для запуска фоновых задач"""
        pass

    def stop(self):
        """Метод для остановки плагина"""
        pass

    def log(self, message, level="info"):
        """Логирование от имени плагина"""
        self.manager.log(self.p_id, message, level)

    def i18n(self, key, default=None):
        """Получение локализованной строки из папки locales плагина"""
        from i18n import i18n_engine
        import os
        import sys
        # Определяем путь к папке плагина
        module = sys.modules[self.__class__.__module__]
        plugin_dir = os.path.dirname(os.path.abspath(module.__file__))
        return i18n_engine.get_string(self.p_id, key, plugin_dir, default)

    def get_metadata(self, lang=None):
        """Возвращает метаданные плагина с учетом локализации"""
        name = self.i18n("plugin_name", self.config.get("name"))
        desc = self.i18n("plugin_description", self.config.get("description"))
        
        return {
            "id": self.p_id,
            "name": name,
            "description": desc,
            "version": self.config.get("version", "1.0.0"),
            "author_name": self.config.get("author_name"),
            "author": self.config.get("author") or self.config.get("author_url"),
            "active": self.p_id in self.manager.plugins,
            "dependencies": self.config.get("dependencies", []),
            "config": self.config
        }

    def handle_command(self, sid: str, target: str, action: str, data: Any = None):
        """Универсальный обработчик команд. Плагины могут расширять его."""
        if action == "get_wizard":
            # Отправляем данные мастера и список текущих активных элементов
            wizard_data = self.get_wizard_data() if hasattr(self, 'get_wizard_data') else {}
            active_items = self.get_active_items() if hasattr(self, 'get_active_items') else []
            
            payload = {
                "wizard": wizard_data,
                "active_items": active_items
            }
            self.manager.emit_to_plugin_ui(self.p_id, "wizard_data", payload, sid=sid)
            return True
            
        elif action in ["handle_wizard", "save_wizard", "save_settings", "update_config"]:
            # Стандартизируем извлечение данных из разных форматов Socket.io
            selections = []
            if isinstance(data, list):
                selections = data
            elif isinstance(data, dict):
                selections = data.get("selections") or data.get("data") or data.get("items") or []
            
            if hasattr(self, 'handle_wizard'):
                self.handle_wizard(selections)
                return True
        
        return False

    def get_initial_events(self) -> List[Dict[str, Any]]:
        """
        Возвращает список событий (event, data), которые нужно отправить 
        новому клиенту сразу после подключения (например, обложка, текст песни).
        """
        return []

    def save_config(self, new_config):
        """Универсальный метод сохранения конфига плагина"""
        with self._lock:
            # Обновляем конфиг в памяти
            if isinstance(new_config, dict):
                self.config.update(new_config)
            
            # Сохраняем на диск
            import os
            import json
            import sys
            try:
                module = sys.modules[self.__class__.__module__]
                plugin_dir = os.path.dirname(os.path.abspath(module.__file__))
                config_path = os.path.join(plugin_dir, "config.json")
                
                # Сначала сериализуем в строку, чтобы не обнулить файл при ошибке
                # Фильтруем конфиг, оставляя только JSON-сериализуемые типы
                def json_safe(d):
                    if isinstance(d, dict):
                        return {k: json_safe(v) for k, v in d.items() if not k.startswith('_') and k != 'class'}
                    elif isinstance(d, list):
                        return [json_safe(i) for i in d]
                    elif isinstance(d, (str, int, float, bool, type(None))):
                        return d
                    return str(d)

                safe_config = json_safe(self.config)
                config_str = json.dumps(safe_config, indent=2, ensure_ascii=False)
                
                with open(config_path, "w", encoding="utf-8") as f:
                    f.write(config_str)
                
                self.log(f"Config saved to {config_path}")
                
                # Уведомляем менеджер об обновлении кэша и UI
                self.manager.update_plugin_config_cache(self.p_id, self.config)
                self.manager.broadcast_ui()
                
            except Exception as e:
                self.log(f"Failed to save config: {e}", level="error")
