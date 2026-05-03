import asyncio
import json
import time
import uuid
import os
from plugin_engine.base_plugin import BasePlugin
from .const import TOKENS_FILE, AUTH_FILE
from .auth import YandexAuth
from .worker import DeviceWorker, monitor_request_state
from core.event_bus import event_bus

class Plugin(BasePlugin):
    """
    Плагин Я.Станции v2 (полностью асинхронный).
    Управляет колонками локально через Glagol API.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self.devices = {}
        self.states = {}
        self.connections = {} 
        self.control_conns = {} 
        self.cmd_queues = {} 
        self.workers = {}
        self._force_broadcast_until = {}
        self.auth = YandexAuth(self)

    async def on_start(self):
        self._load_tokens()
        
        if not self.auth.has_token():
            self.log("Yandex token missing. Starting QR login flow...", 30) # WARNING
            asyncio.create_task(self.auth.start_qr_login())

        config = self.get_config()
        if config.get("tablet_control", False):
            self.log("CONTROL MODE: TABLET. PC local workers suspended.", 20)
            await self._broadcast_config_to_tablet()
            return

        self.log(f"Starting local workers for {len(self.devices)} devices...")
        for d_id in self.devices:
            name = self.devices[d_id].get("name", d_id)
            self.log(f"Spawning worker for: {name} ({d_id})")
            
            self.cmd_queues[d_id] = asyncio.Queue()
            self._force_broadcast_until[d_id] = 0
            
            worker = DeviceWorker(self, d_id)
            self.workers[d_id] = worker
            worker.start() # запускает таски в текущем event loop

    async def on_stop(self):
        self.log("Stopping Yandex Station plugin...")
        for d_id, worker in self.workers.items():
            worker.stop()

    def _load_tokens(self):
        if not TOKENS_FILE.exists():
            if self.auth.has_token():
                self.log("Tokens file not found. Auto-refreshing...", 20)
                asyncio.create_task(self.auth.refresh_tokens_sync())
            return

        try:
            with open(TOKENS_FILE, "r", encoding="utf-8") as f:
                self.devices = json.load(f)
        except Exception as e:
            self.log(f"Error loading tokens: {e}", 40)
            return
        
        for d_id, d in self.devices.items():
            self.states[d_id] = {
                "name": d.get("name", "Колонка"), "platform": d.get("platform"),
                "online": False, "volume": 0, "playing": False,
                "title": "", "artist": "", "cover": "",
                "track_id": "", "alice_state": "IDLE"
            }

    async def handle_command(self, action: str, data: any):
        if action == "get_yandex_config":
            await self._broadcast_config_to_tablet()
            return
        elif action == "handle_wizard":
            # Сохранение настроек мастера
            self.save_config({
                "selected_device_ids": data.get("selected_device_ids", []),
                "tablet_control": data.get("tablet_control", False)
            })
            return
        elif action == "start_qr_login":
            asyncio.create_task(self.auth.start_qr_login())
            return
            
        target = data.get("device_id")
        if not target or target not in self.cmd_queues:
            return

        cmd = action.split(":", 1)[0]
        val = action.split(":", 1)[1] if ":" in action else None
        payload_data = None
        
        if cmd == "play_pause":
            is_playing = self.states.get(target, {}).get("playing", False)
            self.states[target]["playing"] = not is_playing
            payload_data = {"command": "stop" if is_playing else "play"}
            self._force_broadcast_until[target] = time.time() + 3.0
        elif cmd == "next_track":
            payload_data = {"command": "next"}
            self.states[target].update({"title": self.i18n("loading", "Загрузка..."), "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
        elif cmd == "prev_track":
            payload_data = {"command": "prev"}
            self.states[target].update({"title": self.i18n("loading", "Загрузка..."), "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
        elif cmd == "set_volume":
            try:
                v_float = float(val) / 100.0
                payload_data = {"command": "setVolume", "volume": v_float}
                self.states[target]["volume"] = int(float(val))
                self._force_broadcast_until[target] = time.time() + 3.0
            except: pass
        elif cmd == "sync_track":
            if data and isinstance(data, dict):
                track_id = data.get("track_id")
                if track_id:
                    self.states[target]["track_id"] = track_id
                    await self.emit_event("track_changed", {"device_id": target, "track_id": track_id})
            return
            
        await self._push_state()
        if payload_data:
            full_payload = {
                "conversationToken": self.devices[target]["glagol_token"],
                "id": str(uuid.uuid4()),
                "sentTime": int(round(time.time() * 1000)),
                "payload": payload_data
            }
            await self.cmd_queues[target].put(full_payload)
            
            async def delayed_request():
                await asyncio.sleep(0.5)
                await monitor_request_state(self, target)
            asyncio.create_task(delayed_request())

    async def _push_state(self):
        config = self.get_config()
        allowed_ids = config.get("selected_device_ids", [])
        
        if config.get("tablet_control", False):
            minimal_devices = []
            for d_id, d in self.devices.items():
                if not allowed_ids or d_id in allowed_ids:
                    minimal_devices.append({"id": d_id, "name": d.get("name"), "status": "direct"})
            await self.emit_state({"devices": minimal_devices})
            return
            
        devices_list = []
        for d_id, s in self.states.items():
            if allowed_ids and d_id not in allowed_ids: continue
            devices_list.append({
                "id": d_id, "name": s.get("name", ""), "online": s.get("online", False),
                "playing": s.get("playing", False), "volume": s.get("volume", 0),
                "title": s.get("title", ""), "subtitle": s.get("artist", ""),
                "alice_state": s.get("alice_state", "IDLE"),
                "track_id": s.get("track_id", ""),
                "progress": s.get("progress", 0),
                "duration": s.get("duration", 0),
                "last_update": s.get("last_update", time.time())
            })
        await self.emit_state({"devices": devices_list})

    async def _broadcast_config_to_tablet(self):
        config = self.get_config()
        y_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="):
                        y_token = line.split("=")[1].strip()

        configs = []
        if config.get("tablet_control", False):
            allowed_ids = config.get("selected_device_ids", [])
            for d_id, d in self.devices.items():
                if allowed_ids and d_id not in allowed_ids: continue
                if d.get("ip") and d.get("glagol_token"):
                    configs.append({"id": d_id, "glagol_token": d["glagol_token"], "name": d.get("name"), "ip": d["ip"]})
        
        await self.emit_event("yandex_config", {
            "devices": configs, 
            "yandex_token": y_token, 
            "enabled": config.get("tablet_control", False)
        })
