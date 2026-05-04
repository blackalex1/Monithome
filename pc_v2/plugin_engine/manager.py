import asyncio
import logging
import importlib
import sys
import os
from typing import Dict, Any, Type
from plugin_engine.base_plugin import BasePlugin
from core.config import config_manager
from core.event_bus import event_bus

import ctypes
import json

logger = logging.getLogger("PluginManager")

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin() != 0
    except:
        return False

def elevate_and_restart():
    """Перезапуск приложения с правами администратора"""
    logger.info("Requesting Administrator privileges...")
    
    import subprocess
    
    args = sys.argv[:]
    if args[0].endswith(".py"):
        args[0] = os.path.abspath(args[0])
    
    quoted_args = []
    for arg in args:
        if ' ' in arg and not (arg.startswith('"') and arg.endswith('"')):
            quoted_args.append(f'"{arg}"')
        else:
            quoted_args.append(arg)
            
    params = " ".join(quoted_args)
    
    logger.debug(f"Restarting with: {sys.executable} {params}")
    
    ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, os.getcwd(), 1)
    
    if ret > 32:
        logger.info("Elevation successful, exiting current process.")
        os._exit(0)
    else:
        logger.error(f"Elevation failed or cancelled by user. Code: {ret}")

class PluginManager:
    """
    Асинхронный менеджер жизненного цикла плагинов v2.
    Отвечает только за запуск, остановку и маршрутизацию команд к плагинам.
    """
    def __init__(self):
        self.active_plugins: Dict[str, BasePlugin] = {}

    async def pre_check_elevation(self):
        """Проверяет необходимость повышения прав до старта сервера"""
        cfg = config_manager.get()
        active_ids = cfg.active_plugins
        
        for p_id in active_ids:
            try:
                module_name = f"plugins.{p_id}.main"
                module = importlib.import_module(module_name)
                plugin_class: Type[BasePlugin] = getattr(module, "Plugin")
                
                # Создаем временный инстанс для проверки конфига и метода
                instance = plugin_class(plugin_id=p_id)
                p_cfg = instance.get_config()
                req_admin = p_cfg.get("requires_admin", False)
                
                should_elevate = False
                if req_admin is True or req_admin == "true":
                    should_elevate = not is_admin()
                elif req_admin == "if_required":
                    if await instance.check_admin_requirement():
                        should_elevate = not is_admin()
                
                if should_elevate:
                    logger.warning(f"Pre-check: Plugin {p_id} requires Administrator privileges. Restarting...")
                    elevate_and_restart()
                    return True
            except Exception as e:
                logger.debug(f"Pre-check failed for {p_id}: {e}")
        return False

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
            module_name = f"plugins.{plugin_id}.main"
            if module_name in sys.modules:
                importlib.reload(sys.modules[module_name])
            
            module = importlib.import_module(module_name)
            plugin_class: Type[BasePlugin] = getattr(module, "Plugin")
            
            instance = plugin_class(plugin_id=plugin_id)
            
            # Повторная проверка на всякий случай (хотя должна была сработать в pre_check)
            p_cfg = instance.get_config()
            req_admin = p_cfg.get("requires_admin", False)
            should_elevate = False
            if req_admin is True or req_admin == "true":
                should_elevate = not is_admin()
            elif req_admin == "if_required":
                if await instance.check_admin_requirement():
                    should_elevate = not is_admin()
            
            if should_elevate:
                logger.warning(f"Plugin {plugin_id} requires Administrator privileges. Restarting...")
                elevate_and_restart()
                return

            self.active_plugins[plugin_id] = instance
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
        if plugin_id in self.active_plugins:
            instance = self.active_plugins[plugin_id]
            try:
                asyncio.create_task(instance.handle_command(action, data))
            except Exception as e:
                logger.error(f"Error handling command {action} for {plugin_id}: {e}")
        else:
            logger.warning(f"Command '{action}' received for inactive plugin '{plugin_id}'")

    async def shutdown(self):
        for p_id in list(self.active_plugins.keys()):
            await self.stop_plugin(p_id)

plugin_manager = PluginManager()
