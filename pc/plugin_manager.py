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
                    spec.loader.exec_module(module)
                    
                    if hasattr(module, 'Plugin'):
                        discovered[item.name] = {
                            "class": module.Plugin,
                            "config": config
                        }
                        print(f"[CORE] Discovered plugin: {item.name}")
                except Exception as e:
                    print(f"[CORE] Failed to discover plugin {item.name}: {e}")
                    
    return discovered
