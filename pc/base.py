import logging
import threading

class BasePlugin:
    """Базовый класс для всех плагинов"""
    def __init__(self, socketio, config, manager):
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
            "author": self.config.get("author"),
            "active": self.manager.is_plugin_active(self.p_id),
            "dependencies": self.config.get("dependencies", []),
            "config": self.config
        }
