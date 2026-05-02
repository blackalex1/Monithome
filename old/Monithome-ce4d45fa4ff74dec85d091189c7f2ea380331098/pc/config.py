import json
import socket
from pathlib import Path

CONFIG_PATH = Path("master_config.json")

def get_master_config():
    config = {
        "active_plugins": [],
        "language": "ru",
        "trusted_tokens": [],
        "hostname": socket.gethostname(),
        "os": "Windows"
    }
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                file_config = json.load(f)
                config.update(file_config)
        except Exception:
            pass
    return config

def save_master_config(config):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2, ensure_ascii=False)
