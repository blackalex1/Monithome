import os
import json
import logging

class Translator:
    _instance = None
    _language = "ru"
    _cache = {} # (plugin_id, lang) -> dict

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(Translator, cls).__new__(cls)
        return cls._instance

    def set_language(self, lang):
        if self._language != lang:
            self._language = lang
            # При смене языка на всякий случай очистим кэш, 
            # чтобы подхватить возможные изменения в файлах
            self._cache.clear()

    def clear_cache(self):
        self._cache.clear()

    def get_string(self, plugin_id, key, plugin_path, default=None, lang=None):
        target_lang = lang or self._language
        cache_key = (plugin_id, target_lang)
        
        if cache_key not in self._cache:
            self._load_plugin_locales(plugin_id, plugin_path)
            
        res = self._cache.get(cache_key, {}).get(key, default)
        return res

    def _load_plugin_locales(self, plugin_id, plugin_path):
        locales_dir = os.path.join(plugin_path, "locales")
        if os.path.exists(locales_dir):
            for file in os.listdir(locales_dir):
                if file.endswith(".json"):
                    lang_code = file.split(".")[0]
                    try:
                        with open(os.path.join(locales_dir, file), "r", encoding="utf-8") as f:
                            data = json.load(f)
                            self._cache[(plugin_id, lang_code)] = data
                    except Exception as e:
                        pass

# Глобальный объект переводчика
i18n_engine = Translator()
