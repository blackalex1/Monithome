import os
import json
import time
import threading
import requests
import logging
import hmac
import hashlib
import base64
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from base import BasePlugin

logger = logging.getLogger("YandexLyrics")

# Путь к токену Яндекса (используем тот же, что и у станций)
YANDEX_STATION_DIR = Path(__file__).parent.parent / "yandex_station"
AUTH_FILE = YANDEX_STATION_DIR / ".env"

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
        super().__init__(socketio, config, manager)
        
        self._lyrics_cache = {} # d_id -> {track_id, lyrics}
        self._active_fetches = {} # d_id -> track_id
        self._stop_event = threading.Event()
        self.session = requests.Session()
        self.executor = ThreadPoolExecutor(max_workers=5)
        
        self.log("Yandex Lyrics plugin initialized (Parallel Fetching)")
        
        # Подписываемся на смену трека
        self.manager.subscribe("track_changed", self._on_track_changed)
        # Проверяем текущее состояние станций при запускe
        threading.Thread(target=self._check_initial_state, daemon=True).start()

    def _is_tablet_control(self):
        """Проверяем настройку управления с планшета в плагине станций"""
        station_plugin = self.manager.plugins.get("yandex_station")
        if station_plugin:
            return station_plugin.config.get("tablet_control", False)
        return self.config.get("tablet_control", False)

    def start(self):
        """Запуск плагина: проверяем, не перехвачено ли управление планшетом"""
        if self._is_tablet_control():
            self.log("MODE CHANGE: Tablet handles lyrics. PC fetching suspended.")
            return
        
        # Обычный запуск...

    def _check_initial_state(self):
        """Проверка, не играет ли уже что-то на станциях"""
        if self._is_tablet_control(): return
        time.sleep(2) # Даем время другим плагинам загрузиться
        all_stats = self.manager.get_all_stats()
        station_stats = all_stats.get("yandex_station")
        if station_stats and "devices" in station_stats:
            for device in station_stats["devices"]:
                d_id = device.get("id")
                t_id = device.get("track_id")
                if d_id and t_id:
                    # self.log(f"Initial check: found active track {t_id} on {d_id}")
                    self._on_track_changed({"device_id": d_id, "track_id": t_id})

    def _on_track_changed(self, data):
        if self._stop_event.is_set(): return
        if self._is_tablet_control(): return
        device_id = data.get("device_id")
        track_id = data.get("track_id")
        
        if not track_id:
            self._active_fetches[device_id] = None
            if device_id in self._lyrics_cache:
                del self._lyrics_cache[device_id]
                self.update_state({"devices": self._lyrics_cache})
            return

        current = self._lyrics_cache.get(device_id, {})
        if current.get("track_id") == track_id:
            return

        self._active_fetches[device_id] = track_id
        # Используем ключи перевода вместо готовых строк
        self._lyrics_cache[device_id] = {"track_id": track_id, "lyrics": "loading", "timings": []}
        self.update_state({"devices": self._lyrics_cache})
        
        # Debounce
        def delayed_fetch(d_id, t_id):
            time.sleep(0.3)
            if self._stop_event.is_set(): return
            if self._active_fetches.get(d_id) == t_id:
                self._fetch_and_broadcast(d_id, t_id)
                
        threading.Thread(target=delayed_fetch, args=(device_id, track_id), daemon=True).start()

    def _fetch_and_broadcast(self, device_id, track_id):
        # Логируем ключами или просто текстом, лог в консоли сервера не обязательно переводить
        # self.manager.log("Lyrics", "Fetching lyrics...")
        lyrics_data = self._fetch_lyrics_parallel(track_id)
        
        if self._active_fetches.get(device_id) != track_id:
            return

        if lyrics_data:
            self.manager.log("Lyrics", f"Lyrics found for {track_id}")
            lyrics_entry = {
                "track_id": track_id, 
                "lyrics": lyrics_data.get("full"),
                "timings": lyrics_data.get("timings")
            }
            self._lyrics_cache[device_id] = lyrics_entry
            
            # Отправляем событие в UI (здесь данные уже сырые или текст песни, он не переводится)
            self.manager.emit_to_plugin_ui(
                self.p_id, "lyrics", 
                {"device_id": device_id, "data": lyrics_entry}
            )
        else:
            self.manager.log("Lyrics", "Lyrics not available", level="warning")
            # Отправляем пустую строку, чтобы Android понял, что текста нет и не затемнял фон
            self._lyrics_cache[device_id] = {"track_id": track_id, "lyrics": "", "timings": []}
            
        self.update_state({"devices": self._lyrics_cache})

    def _sign_lyrics(self, track_id, timestamp):
        secret = b"p93jhgh689SBReK6ghtw62"
        msg = f"{track_id}{timestamp}".encode()
        hmac_hash = hmac.new(secret, msg, hashlib.sha256).digest()
        return base64.b64encode(hmac_hash).decode()

    def _fetch_from_yandex_supplement(self, raw_track_id, headers):
        try:
            r = self.session.get(f"https://api.music.yandex.net/tracks/{raw_track_id}/supplement", headers=headers, timeout=3)
            if r.status_code == 200:
                res = r.json().get("result", {})
                lyrics_obj = res.get("lyrics")
                if lyrics_obj:
                    data = {"full": lyrics_obj.get("fullLyrics"), "timings": []}
                    major = lyrics_obj.get("major")
                    if major:
                        for line in major.get("lines", []):
                            data["timings"].append({"time": line.get("startTimeMs", 0), "text": line.get("words", "")})
                    return data
        except: pass
        return None

    def _fetch_from_yandex_lrc(self, raw_track_id, headers):
        try:
            ts = int(time.time())
            signature = self._sign_lyrics(raw_track_id, ts)
            url = f"https://api.music.yandex.net/tracks/{raw_track_id}/lyrics"
            r = self.session.get(url, headers=headers, params={"timeStamp": ts, "sign": signature}, timeout=3)
            if r.status_code == 200:
                download_url = r.json().get("result", {}).get("downloadUrl")
                if download_url:
                    lrc_resp = self.session.get(download_url, timeout=3)
                    if lrc_resp.status_code == 200:
                        # Возвращаем сырой LRC текст, планшет сам его распарсит
                        return {"full": lrc_resp.text, "is_lrc": True}
        except: pass
        return None

    def _fetch_from_lrclib(self, raw_track_id, headers):
        try:
            r = self.session.get(f"https://api.music.yandex.net/tracks/{raw_track_id}", headers=headers, timeout=3)
            if r.status_code == 200:
                tr = r.json().get("result", [{}])[0]
                title, artist = tr.get("title"), tr.get("artists", [{}])[0].get("name")
                r_lrc = self.session.get(f"https://lrclib.net/api/get?artist_name={artist}&track_name={title}", timeout=3)
                if r_lrc.status_code == 200:
                    lrc_json = r_lrc.json()
                    synced = lrc_json.get("syncedLyrics")
                    if synced:
                        return {"full": synced, "is_lrc": True}
                    return {"full": lrc_json.get("plainLyrics"), "is_lrc": False}
        except: pass
        return None

    def _fetch_lyrics_parallel(self, track_id):
        if self._stop_event.is_set(): return None
        x_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="): x_token = line.split("=")[1].strip()
        if not x_token: return None

        raw_id = str(track_id).split(":")[0] if ":" in str(track_id) else str(track_id)
        headers = {"Authorization": f"OAuth {x_token}", "X-Yandex-Music-Client": "YandexMusicAndroid/24023621", "User-Agent": "YandexMusicAndroid/24023621", "Accept": "application/json"}

        futures = {
            self.executor.submit(self._fetch_from_yandex_supplement, raw_id, headers): "supplement",
            self.executor.submit(self._fetch_from_yandex_lrc, raw_id, headers): "yandex_lrc",
            self.executor.submit(self._fetch_from_lrclib, raw_id, headers): "lrclib"
        }

        best_result = {"full": None, "is_lrc": False, "timings": []}
        
        for future in as_completed(futures):
            res = future.result()
            if res:
                if res.get("is_lrc") and res.get("full"):
                    best_result["full"] = res["full"]
                    best_result["is_lrc"] = True
                    break
                elif res.get("timings") and not best_result["is_lrc"]:
                    best_result["timings"] = res["timings"]
                    best_result["full"] = res.get("full")
                elif res.get("full") and not best_result["full"]:
                    best_result["full"] = res["full"]

        return best_result if best_result["full"] or best_result["timings"] else None

    def get_stats(self):
        return {"plugin_id": "yandex_lyrics", "devices": self._lyrics_cache}

    def handle_command(self, target, action, data=None):
        if action == "get_wizard":
            self.manager.emit_to_plugin_ui(self.p_id, "wizard_data", self.get_wizard_data())
        elif action in ["handle_wizard", "save_wizard", "save_settings", "update_config"]:
            selections = data if isinstance(data, list) else (data.get("selections") or data.get("data") or []) if isinstance(data, dict) else []
            self.handle_wizard(selections)

    def stop(self):
        self._stop_event.set()
        self.manager.unsubscribe("track_changed", self._on_track_changed)
        self.executor.shutdown(wait=False)
