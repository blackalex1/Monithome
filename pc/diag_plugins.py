import sys
import os
from pathlib import Path

# Добавляем текущую папку в пути, чтобы импорт сработал
sys.path.append(os.getcwd())

try:
    from plugin_manager import load_plugins
    print("Testing load_plugins()...")
    discovered = load_plugins()
    print(f"Discovered {len(discovered)} plugins: {list(discovered.keys())}")
    
    for name, data in discovered.items():
        print(f"  - {name}: class={data.get('class')}, has_config={bool(data.get('config'))}")
        
except Exception as e:
    print(f"DIAGNOSTIC ERROR: {e}")
    import traceback
    traceback.print_exc()
