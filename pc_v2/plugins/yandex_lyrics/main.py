import asyncio
import aiohttp
import time
import hmac
import hashlib
import base64
from pathlib import Path
from plugin_engine.base_plugin import BasePlugin
from core.event_bus import event_bus

YANDEX_STATION_DIR = Path(__file__).parent.parent / "yandex_station"
AUTH_FILE = YANDEX_STATION_DIR / ".env"

class Plugin(BasePlugin):
    """
    Плагин текстов песен Яндекса (v2).
    Полностью асинхронный (aiohttp). Работает через EventBus.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self._lyrics_cache = {}
        self._active_fetches = {}
        self._session = None

    async def on_start(self):
        self._session = aiohttp.ClientSession()
        
        # Подписываемся на события
        event_bus.subscribe("plugin_custom_event", self._on_event)
        event_bus.subscribe("ui_config_changed", self._on_config_changed)
        
        if self._is_tablet_control():
            self.log("MODE NOTICE: Tablet control is enabled. PC lyrics will be disabled.")
            await self.emit_state({"devices": {}})
        else:
            # Сразу пушим пустой/текущий кэш, чтобы планшет знал об активности плагина
            await self.emit_state({"devices": self._lyrics_cache})
        
        self.log("yandex_lyrics started.")

    async def on_stop(self):
        if self._session:
            await self._session.close()
        event_bus.unsubscribe("plugin_custom_event", self._on_event)
        event_bus.unsubscribe("ui_config_changed", self._on_config_changed)
    async def _on_config_changed(self, payload: dict):
        # Реагируем на любые изменения конфига, так как статус станции мог измениться
        await self._check_and_apply_mode()

    async def _check_and_apply_mode(self):
        is_station_active = self._is_station_enabled()
        is_tablet = self._is_tablet_control()
        
        if not is_station_active or is_tablet:
            if self._lyrics_cache:
                reason = "Station disabled" if not is_station_active else "Tablet control active"
                self.log(f"Lyrics suspension: {reason}. Clearing cache.")
                self._lyrics_cache = {}
                await self.emit_state({"devices": {}})
        else:
            # Если всё в порядке - пушим текущее состояние
            await self.emit_state({"devices": self._lyrics_cache})

    def _is_station_enabled(self):
        try:
            cfg_mgr = sys.modules.get('core.config')
            if cfg_mgr:
                active_plugins = getattr(cfg_mgr.config_manager.get(), "active_plugins", [])
                return "yandex_station" in active_plugins
        except: pass
        return True # По умолчанию считаем включенным, если не смогли проверить

    def _is_tablet_control(self):
        try:
            import json
            station_cfg = YANDEX_STATION_DIR / "config.json"
            if station_cfg.exists():
                with open(station_cfg, "r") as f:
                    return json.load(f).get("tablet_control", False)
        except: pass
        return self.get_config().get("tablet_control", False)

    async def _on_event(self, event_payload: dict):
        # Если управление у планшета или плагин станции выключен - полностью игнорируем любые события
        if not self._is_station_enabled() or self._is_tablet_control():
            # На уровне DEBUG можно оставить, но на INFO - тишина
            return

        event_name = event_payload.get("event")

        # event_payload: {"plugin_id": "yandex_station", "event": "track_changed", "data": {"device_id": "...", "track_id": "..."}}
        if event_name == "track_changed":
            data = event_payload.get("data", {})
            await self._handle_track_change(data.get("device_id"), data.get("track_id"))
        elif event_name == "yandex_config":
            # Принудительно проверяем режим при получении конфига
            if self._is_tablet_control():
                self._lyrics_cache = {}
                await self.emit_state({"devices": {}})

    async def _handle_track_change(self, device_id: str, track_id: str):
        if not device_id: return
        self.log(f"Track change detected for {device_id}: {track_id}")
        
        if not track_id:
            self._active_fetches[device_id] = None
            if device_id in self._lyrics_cache:
                del self._lyrics_cache[device_id]
                await self.emit_state({"devices": self._lyrics_cache})
            return

        current = self._lyrics_cache.get(device_id, {})
        if current.get("track_id") == track_id:
            return

        self._active_fetches[device_id] = track_id
        loading_entry = {"track_id": track_id, "lyrics": "loading", "timings": []}
        self._lyrics_cache[device_id] = loading_entry
        await self.emit_state({"devices": self._lyrics_cache})
        
        # Уведомляем планшет о начале загрузки
        await self.emit_event("lyrics", {"device_id": device_id, "data": loading_entry})
        
        async def delayed_fetch():
            await asyncio.sleep(0.3)
            if self._active_fetches.get(device_id) == track_id:
                await self._fetch_and_broadcast(device_id, track_id)
                
        asyncio.create_task(delayed_fetch())

    async def _fetch_and_broadcast(self, device_id: str, track_id: str):
        lyrics_data = await self._fetch_lyrics_parallel(track_id)
        
        if self._active_fetches.get(device_id) != track_id:
            return

        if lyrics_data:
            self.log(f"Lyrics: Found synced lyrics for track {track_id}", 20)
            lyrics_entry = {
                "track_id": track_id, 
                "lyrics": lyrics_data.get("full"),
                "timings": lyrics_data.get("timings")
            }
        else:
            self.log(f"Lyrics not available for {track_id}", 30)
            lyrics_entry = {"track_id": track_id, "lyrics": "", "timings": []}
            
        self._lyrics_cache[device_id] = lyrics_entry
        await self.emit_state({"devices": self._lyrics_cache})
        await self.emit_event("lyrics", {"device_id": device_id, "data": lyrics_entry})

    def _sign_lyrics(self, track_id, timestamp):
        secret = b"p93jhgh689SBReK6ghtw62"
        msg = f"{track_id}{timestamp}".encode()
        hmac_hash = hmac.new(secret, msg, hashlib.sha256).digest()
        return base64.b64encode(hmac_hash).decode()

    def _parse_lrc(self, lrc_text):
        """Парсит LRC формат в список timings"""
        if not lrc_text: return []
        import re
        lines = []
        # Регулярка для [mm:ss.xx] или [mm:ss]
        pattern = re.compile(r'\[(\d+):(\d+)(?:\.(\d+))?\](.*)')
        for line in lrc_text.splitlines():
            match = pattern.search(line)
            if match:
                m, s, ms, text = match.groups()
                ms = int(ms) if ms else 0
                if len(str(ms)) == 2: ms *= 10 # 0.12 -> 120ms
                total_ms = (int(m) * 60 + int(s)) * 1000 + ms
                lines.append({"time": total_ms, "text": text.strip()})
        return lines

    async def _fetch_from_yandex_supplement(self, raw_track_id, headers):
        try:
            async with self._session.get(f"https://api.music.yandex.net/tracks/{raw_track_id}/supplement", headers=headers, timeout=3) as r:
                if r.status == 200:
                    res = (await r.json()).get("result", {})
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

    async def _fetch_from_yandex_lrc(self, raw_track_id, headers):
        try:
            ts = int(time.time())
            signature = self._sign_lyrics(raw_track_id, ts)
            url = f"https://api.music.yandex.net/tracks/{raw_track_id}/lyrics"
            async with self._session.get(url, headers=headers, params={"timeStamp": ts, "sign": signature}, timeout=3) as r:
                if r.status == 200:
                    download_url = (await r.json()).get("result", {}).get("downloadUrl")
                    if download_url:
                        async with self._session.get(download_url, timeout=3) as lrc_resp:
                            if lrc_resp.status == 200:
                                text = await lrc_resp.text()
                                return {"full": text, "is_lrc": True}
        except: pass
        return None

    async def _fetch_from_lrclib(self, raw_track_id, headers):
        try:
            async with self._session.get(f"https://api.music.yandex.net/tracks/{raw_track_id}", headers=headers, timeout=3) as r:
                if r.status == 200:
                    result = (await r.json()).get("result")
                    if isinstance(result, list): result = result[0]
                    if not result: return None
                    
                    title = result.get("title")
                    artists = result.get("artists", [])
                    artist = artists[0].get("name") if artists else ""
                    
                    if not title: return None
                    params = {"artist_name": artist, "track_name": title}
                    async with self._session.get("https://lrclib.net/api/get", params=params, timeout=3) as r_lrc:
                        if r_lrc.status == 200:
                            lrc_json = await r_lrc.json()
                            synced = lrc_json.get("syncedLyrics")
                            if synced:
                                return {"full": synced, "is_lrc": True}
                            return {"full": lrc_json.get("plainLyrics"), "is_lrc": False}
        except Exception as e:
            self.log(f"LRCLIB error ({type(e).__name__}): {str(e)}", 30)
        return None

    async def _fetch_lyrics_parallel(self, track_id):
        x_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="): x_token = line.split("=")[1].strip()
        if not x_token: return None

        raw_id = str(track_id).split(":")[0] if ":" in str(track_id) else str(track_id)
        headers = {"Authorization": f"OAuth {x_token}", "X-Yandex-Music-Client": "YandexMusicAndroid/24023621"}

        # 1. Сначала пробуем Yandex Supplement (лучшее качество таймингов)
        res = await self._fetch_from_yandex_supplement(raw_id, headers)
        if res and res.get("timings"):
            return res

        # 2. Затем пробуем Yandex LRC
        res = await self._fetch_from_yandex_lrc(raw_id, headers)
        if res and res.get("full"):
            res["timings"] = self._parse_lrc(res["full"])
            return res

        # 3. И только в самом конце - LRCLIB
        res = await self._fetch_from_lrclib(raw_id, headers)
        if res:
            if res.get("is_lrc") and res.get("full"):
                res["timings"] = self._parse_lrc(res["full"])
            return res

        return None

    async def handle_command(self, action: str, data: any):
        pass
