import asyncio
import json
import time
import uuid
import websockets
from .utils import get_ssl_ctx, parse_state

class DeviceWorker:
    def __init__(self, plugin, device_id):
        self.plugin = plugin
        self.device_id = device_id
        self.log = plugin.log
        self.loop = None

    def start(self):
        self._tasks = []
        async def run():
            while not getattr(self.plugin, "_stop_event", False):
                try:
                    if self.device_id not in self.plugin.cmd_queues: 
                        self.plugin.cmd_queues[self.device_id] = asyncio.Queue()
                    
                    if self.device_id not in self.plugin.states:
                        self.plugin.states[self.device_id] = {"online": False}
                    
                    await asyncio.gather(
                        self._monitor_loop(), 
                        self._control_loop()
                    )
                except Exception as e:
                    self.log(f"Connection error for {self.device_id}: {e}", 40)
                    if self.device_id in self.plugin.states:
                        self.plugin.states[self.device_id]["online"] = False
                    await self.plugin.broadcaster.push_state()
                    
                    if "4000" in str(e) or "Invalid token" in str(e):
                        if await self.plugin.auth.refresh_tokens_sync():
                            await asyncio.sleep(2)
                            continue
                    await asyncio.sleep(5)
        
        self.log(f"Worker for {self.device_id} is starting...")
        self._tasks.append(asyncio.create_task(run()))

    def stop(self):
        for t in getattr(self, "_tasks", []):
            t.cancel()

    async def _monitor_loop(self):
        device_info = self.plugin.devices.get(self.device_id)
        if not device_info:
            self.log(f"No device info for {self.device_id} in monitor loop. Waiting...", 30)
            await asyncio.sleep(5)
            return

        ip = device_info.get("ip")
        if not ip:
            self.log(f"No IP for {self.device_id}. Waiting for discovery...", 30)
            await asyncio.sleep(5)
            return

        ssl_ctx = get_ssl_ctx()
        try:
            # Увеличиваем таймауты для стабильности на Wi-Fi
            async with websockets.connect(
                f"wss://{ip}:1961", 
                ssl=ssl_ctx, 
                ping_interval=20, 
                ping_timeout=10, 
                close_timeout=5
            ) as ws:
                self.plugin.connections[self.device_id] = ws
                self.plugin.states[self.device_id]["online"] = True
                self.log(f"Connected to speaker {self.device_id} at {ip}")
                await self.plugin.broadcaster.push_state()
                
                async def heartbeat():
                    while True:
                        try:
                            dev = self.plugin.devices.get(self.device_id)
                            if not dev or "glagol_token" not in dev: break
                            
                            is_forcing = time.time() < self.plugin._force_broadcast_until.get(self.device_id, 0)
                            is_playing = self.plugin.states[self.device_id].get("playing", False)
                            
                            # Стабильный опрос раз в 2 секунды при игре
                            sleep_time = 0.5 if is_forcing else (2.0 if is_playing else 4.0)
                            await asyncio.sleep(sleep_time)
                            
                            await ws.send(json.dumps({
                                "conversationToken": dev["glagol_token"],
                                "id": str(uuid.uuid4()),
                                "sentTime": int(round(time.time() * 1000)),
                                "payload": {"command": "getState"}
                            }))
                        except: break
                
                hb_task = asyncio.create_task(heartbeat())
                try:
                    async for message in ws:
                        data = json.loads(message)
                        if "state" in data:
                            if await self._internal_parse_state(data["state"]):
                                await self.plugin.broadcaster.push_state()
                finally: hb_task.cancel()
        except Exception as e:
            raise e 

    async def _control_loop(self):
        device_info = self.plugin.devices.get(self.device_id)
        if not device_info or not device_info.get("ip"):
            await asyncio.sleep(5)
            return

        ip = device_info.get("ip")
        ssl_ctx = get_ssl_ctx()
        try:
            async with websockets.connect(
                f"wss://{ip}:1961", 
                ssl=ssl_ctx, 
                ping_interval=20, 
                ping_timeout=10, 
                close_timeout=5
            ) as ws:
                self.plugin.control_conns[self.device_id] = ws
                queue = self.plugin.cmd_queues[self.device_id]
                
                async def trash_consumer():
                    try:
                        async for _ in ws: pass
                    except: pass
                
                tc_task = asyncio.create_task(trash_consumer())
                try:
                    while True:
                        payload = await queue.get()
                        try: await ws.send(json.dumps(payload))
                        except: break
                        queue.task_done()
                finally: tc_task.cancel()
        except:
            pass

    async def _internal_parse_state(self, s):
        new_vals = parse_state(s)
        core_changed = False
        core_keys = ["playing", "title", "artist", "volume", "track_id", "progress"]
        for k in core_keys:
            if self.plugin.states[self.device_id].get(k) != new_vals.get(k):
                core_changed = True
                break
        
        old_track = self.plugin.states[self.device_id].get("track_id")
        is_new_track = bool(new_vals.get("track_id")) and new_vals.get("track_id") != old_track

        self.plugin.states[self.device_id].update(new_vals)
        self.plugin.states[self.device_id]["last_update"] = time.time()

        if is_new_track:
            await self.plugin.emit_event("track_changed", {
                "device_id": self.device_id, 
                "track_id": new_vals["track_id"],
                "title": new_vals.get("title"),
                "artist": new_vals.get("artist")
            })

        new_cover = new_vals.get("cover", "")
        old_cover = self.plugin.states[self.device_id].get("_sent_cover", "")
        if new_cover and new_cover != old_cover:
            self.plugin.states[self.device_id]["_sent_cover"] = new_cover
            await self.plugin.emit_event("cover", {
                "device_id": self.device_id,
                "cover": new_cover,
                "title": new_vals.get("title", "")
            })

        now = time.time()
        last_broadcast = getattr(self.plugin, "_last_broadcast_time", 0)
        if core_changed or (now - last_broadcast > 5.0) or (self.device_id in self.plugin._force_broadcast_until and now < self.plugin._force_broadcast_until[self.device_id]):
            self.plugin._last_broadcast_time = now
            return True
        return False

async def monitor_request_state(plugin, device_id):
    if device_id in plugin.cmd_queues:
        dev = plugin.devices.get(device_id)
        if not dev or "glagol_token" not in dev: return
        
        await plugin.cmd_queues[device_id].put({
            "conversationToken": dev["glagol_token"],
            "id": str(uuid.uuid4()),
            "sentTime": int(round(time.time() * 1000)),
            "payload": {"command": "getState"}
        })
