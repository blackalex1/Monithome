import os
import json
import time
import threading
import requests
import logging
from pathlib import Path

logger = logging.getLogger("YandexLyrics")

# Путь к токену Яндекса (используем тот же, что и у станций)
YANDEX_STATION_DIR = Path(__file__).parent.parent / "yandex_station"
AUTH_FILE = YANDEX_STATION_DIR / ".env"

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        
        self._lyrics_cache = {} # d_id -> {track_id, lyrics}
        self._stop_event = threading.Event()
        
        self.manager.log("Lyrics", "Yandex Lyrics plugin initialized")
        
        # Поток для мониторинга состояния станций
        self._monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._monitor_thread.start()

    def _monitor_loop(self):
        last_broadcast = 0
        while not self._stop_event.is_set():
            try:
                if self.manager:
                    y_plugin = self.manager.get_plugin("yandex_station")
                    if y_plugin:
                        stats = y_plugin.get_stats()
                        devices = stats.get("devices", [])
                        
                        for device in devices:
                            d_id = device.get("id")
                            track_id = device.get("track_id")
                            
                            if track_id and track_id != "":
                                cached = self._lyrics_cache.get(d_id, {})
                                if cached.get("track_id") != track_id:
                                    self.manager.log("Lyrics", f"New track detected: {track_id}")
                                    self._lyrics_cache[d_id] = {"track_id": track_id, "lyrics": "Loading..."}
                                    # Рассылаем статус загрузки немедленно
                                    self.manager.broadcast_stats(self.get_stats())
                                    threading.Thread(target=self._fetch_and_broadcast, args=(d_id, track_id), daemon=True).start()
                            else:
                                if d_id in self._lyrics_cache:
                                    del self._lyrics_cache[d_id]
                                    self.manager.broadcast_stats(self.get_stats())
                                    
                        # Периодическая рассылка для синхронизации новых клиентов (раз в 5 сек)
                        if time.time() - last_broadcast > 5:
                            if self._lyrics_cache:
                                self.manager.broadcast_stats(self.get_stats())
                            last_broadcast = time.time()
            except Exception as e:
                self.manager.log("Lyrics", f"Error: {e}", level="error")
            
            time.sleep(1) # Проверяем чаще

    def _fetch_and_broadcast(self, device_id, track_id):
        self.manager.log("Lyrics", f"Fetching lyrics for track {track_id}...")
        lyrics_data = self._fetch_lyrics(track_id)
        
        if lyrics_data:
            self.manager.log("Lyrics", f"Lyrics found for {track_id}. Broadcasting...")
            self._lyrics_cache[device_id] = {
                "track_id": track_id, 
                "lyrics": lyrics_data.get("full"),
                "timings": lyrics_data.get("timings")
            }
        else:
            self.manager.log("Lyrics", f"Lyrics not available for {track_id}", level="warning")
            # Вместо удаления — помечаем, что мы уже пробовали искать (lyrics = None)
            # Это предотвратит повторные попытки в монитор-лупе для этого же трека
            self._lyrics_cache[device_id] = {
                "track_id": track_id, 
                "lyrics": None,
                "timings": []
            }
        
        # В любом случае рассылаем актуальный стейт
        self.manager.broadcast_stats(self.get_stats())

    def _fetch_lyrics(self, track_id):
        if not track_id: return None
        x_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="):
                        x_token = line.split("=")[1].strip()
        
        if not x_token:
            self.manager.log("Lyrics", "YANDEX_TOKEN not found in .env", level="error")
            return None

        # Очищаем track_id (может прийти в формате id:album)
        if ":" in str(track_id):
            track_id = str(track_id).split(":")[0]

        try:
            headers = {
                "Authorization": f"OAuth {x_token}",
                "X-Yandex-Music-Client": "YandexMusicAndroid/24023621",
                "User-Agent": "YandexMusicAndroid/24023621",
                "Accept": "application/json"
            }
            r = requests.get(f"https://api.music.yandex.net/tracks/{track_id}/supplement", headers=headers, timeout=5)
            if r.status_code == 200:
                data = r.json()
                res = data.get("result", {})
                lyrics_obj = res.get("lyrics")
                
                if lyrics_obj:
                    # Получаем обычный текст
                    full = lyrics_obj.get("fullLyrics")
                    
                    timings = []
                    major = lyrics_obj.get("major")
                    if major:
                        for line in major.get("lines", []):
                            timings.append({
                                "time": line.get("startTimeMs", 0),
                                "text": line.get("words", "")
                            })
                    
                    self.manager.log("Lyrics", f"Successfully parsed lyrics for {track_id} (timings: {len(timings)})")
                    return {"full": full, "timings": timings}
                else:
                    self.manager.log("Lyrics", f"Track {track_id} has no lyrics object in Yandex API")
            else:
                self.manager.log("Lyrics", f"Yandex API error {r.status_code} for track {track_id}", level="error")
        except Exception as e:
            self.manager.log("Lyrics", f"Request failed for track {track_id}: {e}", level="error")
        return None

    def get_stats(self):
        # Плагин отдает текущий кэш
        return {"plugin_id": "yandex_lyrics", "devices": self._lyrics_cache}

    def handle_command(self, target, action):
        pass

    def stop(self):
        self._stop_event.set()
