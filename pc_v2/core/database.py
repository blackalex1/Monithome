import sqlite3
import os
import json
import logging
from typing import Any, Optional

logger = logging.getLogger("Database")

class SettingsDB:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        """Инициализация таблиц, если они не существуют"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        
        # Таблица для глобальных настроек
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS global_settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        ''')
        
        # Таблица для настроек плагинов
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS plugin_settings (
                plugin_id TEXT PRIMARY KEY,
                settings_json TEXT
            )
        ''')
        
        # Таблица для зашифрованных секретов
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS secrets (
                key TEXT PRIMARY KEY,
                encrypted_value BLOB
            )
        ''')
        
        conn.commit()
        conn.close()

    def get_raw_secret(self, key: str) -> Optional[bytes]:
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute("SELECT encrypted_value FROM secrets WHERE key = ?", (key,))
            row = cursor.fetchone()
            conn.close()
            if row:
                return row[0]
        except Exception as e:
            logger.error(f"Error reading raw secret {key}: {e}")
        return None

    def set_raw_secret(self, key: str, encrypted_value: bytes):
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute(
                "INSERT OR REPLACE INTO secrets (key, encrypted_value) VALUES (?, ?)",
                (key, encrypted_value)
            )
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"Error saving raw secret {key}: {e}")

    def get_global(self, key: str, default: Any = None) -> Any:
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute("SELECT value FROM global_settings WHERE key = ?", (key,))
            row = cursor.fetchone()
            conn.close()
            if row:
                return json.loads(row[0])
        except Exception as e:
            logger.error(f"Error reading global setting {key}: {e}")
        return default

    def set_global(self, key: str, value: Any):
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute(
                "INSERT OR REPLACE INTO global_settings (key, value) VALUES (?, ?)",
                (key, json.dumps(value))
            )
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"Error saving global setting {key}: {e}")

    def get_plugin_settings(self, plugin_id: str) -> Optional[dict]:
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute("SELECT settings_json FROM plugin_settings WHERE plugin_id = ?", (plugin_id,))
            row = cursor.fetchone()
            conn.close()
            if row:
                return json.loads(row[0])
        except Exception as e:
            logger.error(f"Error reading plugin settings for {plugin_id}: {e}")
        return None

    def set_plugin_settings(self, plugin_id: str, settings: dict):
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()
            cursor.execute(
                "INSERT OR REPLACE INTO plugin_settings (plugin_id, settings_json) VALUES (?, ?)",
                (plugin_id, json.dumps(settings))
            )
            conn.commit()
            conn.close()
        except Exception as e:
            logger.error(f"Error saving plugin settings for {plugin_id}: {e}")
