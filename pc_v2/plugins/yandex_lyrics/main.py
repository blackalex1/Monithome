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
        # Проверяем, не перехвачено ли управление планшетом
        if self._is_tablet_control():
            self.log("MODE CHANGE: Tablet handles lyrics. PC fetching suspended.")
            return
            
        # Подписываемся на события изменения трека
        self.manager.subscribe("plugin_custom_event", self._on_event)
        self.log("yandex_lyrics started.")

    async def on_stop(self):
        if self._session:
            await self._session.close()
        self.manager.unsubscribe("plugin_custom_event", self._on_event)
        self.log("yandex_lyrics stopped.")

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
        # event_payload: {"plugin_id": "yandex_station", "event": "track_changed", "data": {"device_id": "...", "track_id": "..."}}
        if event_payload.get("event") == "track_changed":
            data = event_payload.get("data", {})
            await self._handle_track_change(data.get("device_id"), data.get("track_id"))

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
            self.log(f"Lyrics found for {track_id}")
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
                    tr = (await r.json()).get("result", [{}])[0]
                    title, artist = tr.get("title"), tr.get("artists", [{}])[0].get("name")
                    async with self._session.get(f"https://lrclib.net/api/get?artist_name={artist}&track_name={title}", timeout=3) as r_lrc:
                        if r_lrc.status == 200:
                            lrc_json = await r_lrc.json()
                            synced = lrc_json.get("syncedLyrics")
                            if synced:
                                return {"full": synced, "is_lrc": True}
                            return {"full": lrc_json.get("plainLyrics"), "is_lrc": False}
        except: pass
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

        results = await asyncio.gather(
            self._fetch_from_yandex_supplement(raw_id, headers),
            self._fetch_from_yandex_lrc(raw_id, headers),
            self._fetch_from_lrclib(raw_id, headers),
            return_exceptions=True
        )

        best_result = {"full": None, "is_lrc": False, "timings": []}
        
        for res in results:
            if isinstance(res, dict) and res:
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

    async def handle_command(self, action: str, data: any):
        pass
