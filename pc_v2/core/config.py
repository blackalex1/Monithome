import json
import os
from typing import List, Optional
from pydantic import BaseModel, Field

CONFIG_FILE = "master_config.json"

class MasterConfig(BaseModel):
    hostname: str = "MonitHome PC"
    os: str = "windows"
    language: str = "ru"
    encryption_key: Optional[str] = None
    trusted_tokens: List[str] = Field(default_factory=list)
    active_plugins: List[str] = Field(default_factory=list)
    plugin_order: List[str] = Field(default_factory=list)
    _v: int = 0

class ConfigManager:
    def __init__(self, config_path: str = CONFIG_FILE):
        self.config_path = config_path
        self.config = self._load()
        self._load_secrets()

    def _load(self) -> MasterConfig:
        if os.path.exists(self.config_path):
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    return MasterConfig(**data)
            except Exception as e:
                print(f"[ConfigManager] Error loading config: {e}. Using defaults.")
        return MasterConfig()

    def _load_secrets(self):
        """Загружает секреты из .env файла в корне проекта"""
        # Ищем .env в корне (на уровень выше от pc_v2) или в текущей папке
        env_paths = [".env", "../.env"]
        for path in env_paths:
            if os.path.exists(path):
                try:
                    with open(path, "r", encoding="utf-8") as f:
                        for line in f:
                            if "=" in line:
                                k, v = line.split("=", 1)
                                k = k.strip()
                                v = v.strip()
                                if k == "ENCRYPTION_KEY":
                                    self.config.encryption_key = v
                                    print("[ConfigManager] Encryption key loaded from .env")
                                elif k == "TRUSTED_TOKENS":
                                    # Можно хранить токены через запятую
                                    tokens = [t.strip() for t in v.split(",") if t.strip()]
                                    self.config.trusted_tokens.extend(tokens)
                except Exception as e:
                    print(f"[ConfigManager] Error loading secrets from {path}: {e}")
                break

    def save(self):
        self.config._v += 1
        with open(self.config_path, "w", encoding="utf-8") as f:
            f.write(self.config.model_dump_json(indent=4))

    def get(self) -> MasterConfig:
        return self.config

# Global instance
config_manager = ConfigManager()
