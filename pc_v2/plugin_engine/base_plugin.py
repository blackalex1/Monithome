import asyncio
from abc import ABC, abstractmethod
from typing import Any, Dict
import logging
from core.event_bus import event_bus

class BasePlugin(ABC):
    """
    Единый стандарт (Унификация) для всех плагинов v2.
    Все плагины должны наследоваться от этого класса.
    """
    def __init__(self, plugin_id: str):
        self.plugin_id = plugin_id
        self.logger = logging.getLogger(f"Plugin.{plugin_id}")
        self._is_running = False

    async def start(self):
        """Внутренний метод запуска. Не переопределять в плагинах."""
        if self._is_running:
            return
        self._is_running = True
        self.log("Starting...")
        await self.on_start()

    async def stop(self):
        """Внутренний метод остановки. Не переопределять в плагинах."""
        if not self._is_running:
            return
        self._is_running = False
        self.log("Stopping...")
        await self.on_stop()

    @abstractmethod
    async def on_start(self):
        """Вызывается при запуске плагина. Здесь нужно запускать фоновые таски."""
        pass

    @abstractmethod
    async def on_stop(self):
        """Вызывается при остановке плагина. Здесь нужно отменять таски и закрывать сокеты."""
        pass

    @abstractmethod
    async def handle_command(self, action: str, data: Any):
        """Обработка команд от UI (Android / Web)."""
        pass

    # --- УТИЛИТЫ ДЛЯ ПЛАГИНОВ ---

    def log(self, message: str, level: int = logging.INFO):
        """Стандартное логирование для плагинов"""
        self.logger.log(level, message)

    async def emit_state(self, state: Dict[str, Any]):
        """Отправка обновленного состояния плагина."""
        await event_bus.emit("plugin_state_changed", {
            "plugin_id": self.plugin_id,
            "state": state
        })

    async def emit_event(self, event_name: str, data: Any):
        """Отправка специфичного события плагина."""
        await event_bus.emit("plugin_custom_event", {
            "plugin_id": self.plugin_id,
            "event": event_name,
            "data": data
        })

    def get_config(self) -> Dict[str, Any]:
        """Возвращает конфигурацию плагина из config.json"""
        import os
        import json
        config_path = os.path.join(os.path.dirname(sys.modules[self.__module__].__file__), "config.json")
        if os.path.exists(config_path):
            try:
                with open(config_path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception as e:
                self.log(f"Error reading config: {e}", level=logging.ERROR)
        return {}

    def save_config(self, new_config: Dict[str, Any]):
        """Сохраняет конфигурацию плагина и применяет изменения (перезапись)"""
        import os
        import json
        config_path = os.path.join(os.path.dirname(sys.modules[self.__module__].__file__), "config.json")
        current = self.get_config()
        current.update(new_config)
        try:
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(current, f, indent=4, ensure_ascii=False)
            self.log("Config saved.")
        except Exception as e:
            self.log(f"Error saving config: {e}", level=logging.ERROR)

    def i18n(self, key: str, default: str = None) -> str:
        """Временная заглушка локализации (возвращает default или ключ)"""
        # TODO: Реализовать полноценный i18n-движок для v2
        return default if default else key
