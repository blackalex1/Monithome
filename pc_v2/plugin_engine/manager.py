import asyncio
import logging
import importlib
import sys
import os
from typing import Dict, Any, Type
from plugin_engine.base_plugin import BasePlugin
from core.config import config_manager
from core.event_bus import event_bus

logger = logging.getLogger("PluginManager")

class PluginManager:
    """
    Асинхронный менеджер жизненного цикла плагинов v2.
    Отвечает только за запуск, остановку и маршрутизацию команд к плагинам.
    """
    def __init__(self):
        self.active_plugins: Dict[str, BasePlugin] = {}

    async def initialize(self):
        """Считывает конфиг и запускает активные плагины"""
        cfg = config_manager.get()
        active_ids = cfg.active_plugins
        logger.info(f"Initializing plugins: {active_ids}")
        
        for p_id in active_ids:
            await self.start_plugin(p_id)

    async def start_plugin(self, plugin_id: str):
        if plugin_id in self.active_plugins:
            return

        try:
            # Динамический импорт плагина
            # Ожидаем структуру: plugins/<plugin_id>/main.py с классом Plugin(BasePlugin)
            module_name = f"plugins.{plugin_id}.main"
            if module_name in sys.modules:
                importlib.reload(sys.modules[module_name])
            
            module = importlib.import_module(module_name)
            plugin_class: Type[BasePlugin] = getattr(module, "Plugin")
            
            # Инстанцируем и запускаем
            instance = plugin_class(plugin_id=plugin_id)
            self.active_plugins[plugin_id] = instance
            
            # Запускаем в фоне, чтобы не блокировать менеджер
            asyncio.create_task(instance.start())
            
            logger.info(f"Successfully started plugin: {plugin_id}")
        except Exception as e:
            logger.error(f"Failed to start plugin {plugin_id}: {e}")

    async def stop_plugin(self, plugin_id: str):
        if plugin_id in self.active_plugins:
            instance = self.active_plugins.pop(plugin_id)
            try:
                await instance.stop()
                logger.info(f"Successfully stopped plugin: {plugin_id}")
            except Exception as e:
                logger.error(f"Error stopping plugin {plugin_id}: {e}")

    async def handle_command(self, plugin_id: str, action: str, data: Any):
        """Перенаправляет команду от UI к конкретному плагину"""
        if plugin_id in self.active_plugins:
            instance = self.active_plugins[plugin_id]
            try:
                # Fire and forget (в отдельной таске), чтобы не блокировать сокеты
                asyncio.create_task(instance.handle_command(action, data))
            except Exception as e:
                logger.error(f"Error handling command {action} for {plugin_id}: {e}")
        else:
            logger.warning(f"Command '{action}' received for inactive plugin '{plugin_id}'")

    async def shutdown(self):
        """Остановка всех плагинов при выходе"""
        for p_id in list(self.active_plugins.keys()):
            await self.stop_plugin(p_id)

# Global instance
plugin_manager = PluginManager()
