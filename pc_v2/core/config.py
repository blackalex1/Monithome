import json
import os
from typing import List, Optional
from pydantic import BaseModel, Field

# Путь к конфигу всегда в корне проекта (на уровень выше от core/)
BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONFIG_FILE = os.path.join(BASE_DIR, "master_config.json")

class MasterConfig(BaseModel):
    hostname: str = "MonitHome PC"
    os: str = "windows"
    language: str = "ru"
    encryption_key: Optional[str] = Field(default=None, exclude=True)
    trusted_tokens: List[str] = Field(default_factory=list, exclude=True)
    active_plugins: List[str] = Field(default=[
        "sys_info", "system_stats", "yandex_lyrics", 
        "yandex_station", "pc_system", "pc_media", "pc_disks"
    ])
    plugin_order: List[str] = Field(default_factory=list)
    theme_color: str = "0xFF22C55E"
    _v: int = 0

class ConfigManager:
    def __init__(self, config_path: str = None):
        self.config_path = config_path or os.path.join(BASE_DIR, "master_config.json")
        print(f"[ConfigManager] Using config at: {self.config_path}")
        self.config = self._load()
        self._load_secrets()
        
        # Пытаемся прочитать существующий токен сессии или генерируем новый
        self.gui_token = self._setup_gui_token()

    def _setup_gui_token(self) -> str:
        import secrets
        # Всегда используем абсолютный путь к токену в корне
        token_path = os.path.join(BASE_DIR, ".gui_token")
        
        if os.path.exists(token_path):
            try:
                with open(token_path, "r") as f:
                    token = f.read().strip()
                    if token: return token
            except: pass
            
        new_token = secrets.token_hex(16)
        try:
            with open(token_path, "w") as f:
                f.write(new_token)
        except: pass
        return new_token

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
        env_path = os.path.join(BASE_DIR, ".env")
        if os.path.exists(env_path):
            try:
                with open(env_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line or line.startswith("#"): continue
                        if "=" in line:
                            k, v = line.split("=", 1)
                            k = k.strip()
                            v = v.strip()
                            if k == "ENCRYPTION_KEY":
                                self.config.encryption_key = v
                            elif k == "TRUSTED_TOKENS":
                                tokens = [t.strip() for t in v.split(",") if t.strip()]
                                # Очищаем и заменяем, чтобы не дублировать при перезагрузках
                                self.config.trusted_tokens = list(set(tokens))
                                print(f"[ConfigManager] Loaded {len(self.config.trusted_tokens)} trusted tokens")
            except Exception as e:
                print(f"[ConfigManager] Error loading secrets from {env_path}: {e}")

    def save(self):
        self.config._v += 1
        with open(self.config_path, "w", encoding="utf-8") as f:
            f.write(self.config.model_dump_json(
                indent=4, 
                exclude={'encryption_key', 'trusted_tokens'}
            ))

    def save_secret(self, key: str, value: str):
        """Сохраняет секрет в .env файл"""
        # Обновляем в текущем конфиге
        if key == "ENCRYPTION_KEY":
            self.config.encryption_key = value
            self._write_env_var(key, value)
        elif key == "TRUSTED_TOKENS":
            if value not in self.config.trusted_tokens:
                self.config.trusted_tokens.append(value)
            
            tokens_str = ",".join(self.config.trusted_tokens)
            self._write_env_var("TRUSTED_TOKENS", tokens_str)

    def _write_env_var(self, key: str, value: str):
        env_path = os.path.join(BASE_DIR, ".env")
        lines = []
        found = False
        if os.path.exists(env_path):
            with open(env_path, "r", encoding="utf-8") as f:
                lines = f.readlines()
        
        new_lines = []
        for line in lines:
            if line.strip().startswith(f"{key}="):
                new_lines.append(f"{key}={value}\n")
                found = True
            else:
                new_lines.append(line)
        if not found:
            new_lines.append(f"{key}={value}\n")
            
        with open(env_path, "w", encoding="utf-8") as f:
            f.writelines(new_lines)

    def get(self) -> MasterConfig:
        return self.config

# Global instance
config_manager = ConfigManager()
