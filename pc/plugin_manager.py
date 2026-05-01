import os
import json
import importlib.util
from pathlib import Path

def load_plugins():
    """
    Только обнаруживает плагины и возвращает словарь с их классами и конфигами.
    Инициализация происходит в pc_agent.py.
    """
    discovered = {}
    plugins_dir = Path(__file__).parent / "plugins"
    print(f"[CORE] Searching for plugins in: {plugins_dir.absolute()}")
    
    if not plugins_dir.exists():
        print(f"[CORE] Plugins directory not found: {plugins_dir.absolute()}")
        return {}

    for item in plugins_dir.iterdir():
        if item.is_dir() and not item.name.startswith("__"):
            logic_path = item / "logic.py"
            config_path = item / "config.json"
            
            if logic_path.exists() and config_path.exists():
                try:
                    # Загружаем конфиг
                    with open(config_path, "r", encoding="utf-8") as f:
                        config = json.load(f)
                    
                    # Динамический импорт
                    module_name = f"plugins.{item.name}.logic"
                    spec = importlib.util.spec_from_file_location(module_name, str(logic_path))
                    module = importlib.util.module_from_spec(spec)
                    import sys
                    sys.modules[module_name] = module
                    spec.loader.exec_module(module)
                    
                    if hasattr(module, 'Plugin'):
                        discovered[item.name] = {
                            "class": module.Plugin,
                            "config": config,
                            "path": str(item.absolute())
                        }
                        print(f"[CORE] Discovered plugin: {item.name}")
                except Exception as e:
                    print(f"[CORE] Failed to discover plugin {item.name}: {e}")
                    
    return discovered

# Глобальный кэш обнаруженных плагинов
discovered_plugins = load_plugins()

def instantiate_plugin(p_id, manager):
    """Создает экземпляр плагина"""
    if p_id in discovered_plugins:
        p_data = discovered_plugins[p_id]
        p_class = p_data["class"]
        p_config = p_data["config"]
        # Согласно logic.py: __init__(self, socketio, config, manager)
        p_instance = p_class(manager.socketio, p_config, manager)
        return p_instance
    return None
