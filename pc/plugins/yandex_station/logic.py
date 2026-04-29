import asyncio
import json
import ssl
import time
import uuid
import threading
import websockets
import requests
from zeroconf import ServiceBrowser, Zeroconf

from .const import TOKENS_FILE, AUTH_FILE, CONFIG_FILE
from .discovery import SpeakerDiscovery, get_all_interfaces
from .utils import get_ssl_ctx, parse_state

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._stop_event = threading.Event()
        self.devices = {}
        self.states = {}
        self.connections = {} 
        self.control_conns = {} 
        self.loops = {} 
        self.cmd_queues = {} 
        self._refreshing_tokens = False
        self._last_refresh_time = 0
        
        self._load_tokens()
        self._last_state_hash = ""
        self._force_broadcast_until = {} # Время, до которого шлем всё без фильтров
        
        for d_id in self.devices:
            threading.Thread(target=self._device_worker, args=(d_id,), daemon=True).start()

    def _load_tokens(self):
        if TOKENS_FILE.exists():
            try:
                with open(TOKENS_FILE, "r", encoding="utf-8") as f:
                    self.devices = json.load(f)
                    for d_id, d in self.devices.items():
                        self.states[d_id] = {
                            "name": d["name"], "platform": d["platform"],
                            "online": False, "volume": 0, "playing": False,
                            "title": "", "artist": "", "cover": "", "alice_state": "IDLE"
                        }
            except Exception as e:
                self.manager.log("YANDEX", f"Error loading tokens: {e}", level="error")

    def _refresh_tokens_sync(self):
        """Автоматическое обновление токенов без участия пользователя"""
        now = time.time()
        if self._refreshing_tokens or (now - self._last_refresh_time < 10):
            return False
        
        self._refreshing_tokens = True
        self._last_refresh_time = now
        self.manager.log("YANDEX", "Starting automatic token refresh cycle...")
        
        try:
            # 1. Получаем X-Token из .env
            x_token = None
            if AUTH_FILE.exists():
                with open(AUTH_FILE, "r") as f:
                    for line in f:
                        if line.startswith("YANDEX_TOKEN="):
                            x_token = line.split("=")[1].strip()
            
            if not x_token:
                self.manager.log("YANDEX", "Refresh FAILED: No YANDEX_TOKEN found in .env", level="error")
                return False

            # 2. Ищем колонки в сети (mDNS)
            interfaces = get_all_interfaces()
            self.manager.log("YANDEX", f"Discovery on interfaces: {interfaces}")
            zeroconf = Zeroconf(interfaces=interfaces)
            discovery = SpeakerDiscovery()
            browser = ServiceBrowser(zeroconf, "_yandexio._tcp.local.", discovery)
            time.sleep(7)
            browser.cancel() # Останавливаем браузер до закрытия Zeroconf
            zeroconf.close()
            local_devices = discovery.found_devices
            self.manager.log("YANDEX", f"Found {len(local_devices)} devices in local network.")

            # 3. Запрашиваем новые Glagol-токены у Яндекса
            headers = {
                "Authorization": f"OAuth {x_token}",
                "X-Yandex-Token": x_token,
                "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
            }
            
            self.manager.log("YANDEX", "Requesting device list from Yandex...")
            r = requests.get("https://quasar.yandex.net/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200:
                r = requests.get("https://quasar.yandex.ru/glagol/device_list", headers=headers, timeout=10)
            
            if r.status_code != 200:
                self.manager.log("YANDEX", f"Cloud API error: Status {r.status_code}.", level="error")
                return False
            
            quasar_list = r.json().get("devices", [])
            new_results = {}
            
            for q_dev in quasar_list:
                d_id = q_dev.get("id", "").strip()
                if not d_id: continue
                
                # Ищем токен
                g_token = q_dev.get("glagol_token") or q_dev.get("glagol", {}).get("token")
                
                # FALLBACK: Если токена нет в общем списке, запрашиваем его индивидуально
                if not g_token:
                    platform = q_dev.get("platform")
                    self.manager.log("YANDEX", f"Token missing for {d_id}, trying individual request...")
                    url_single = f"https://quasar.yandex.ru/glagol/token?device_id={d_id}&platform={platform}"
                    try:
                        r_s = requests.get(url_single, headers=headers, timeout=5)
                        if r_s.status_code == 200:
                            g_token = r_s.json().get("token")
                            self.manager.log("YANDEX", f"Successfully fetched individual token for {d_id}")
                    except Exception as e:
                        self.manager.log("YANDEX", f"Individual fetch error for {d_id}: {e}")

                if not g_token:
                    continue

                # Пытаемся найти IP
                ip = local_devices.get(d_id, {}).get("ip")
                if not ip:
                    for cached_id, cached_data in self.devices.items():
                        if cached_id.strip() == d_id:
                            ip = cached_data.get("ip")
                            break

                if ip:
                    new_results[d_id] = {
                        "name": q_dev.get("name", "Колонка").strip(),
                        "glagol_token": g_token,
                        "platform": q_dev.get("platform"),
                        "ip": ip
                    }

            if new_results:
                with open(TOKENS_FILE, "w", encoding="utf-8") as f:
                    json.dump(new_results, f, ensure_ascii=False, indent=2)
                self.devices = new_results
                self.manager.log("YANDEX", f"Successfully refreshed tokens for {len(new_results)} devices.")
                return True
            else:
                self.manager.log("YANDEX", "No devices could be updated.", level="warning")
        except Exception as e:
            self.manager.log("YANDEX", f"Auto-refresh exception: {e}", level="error")
        finally:
            self._refreshing_tokens = False
        return False

    def _device_worker(self, device_id):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self.loops[device_id] = loop
        
        async def run():
            while not self._stop_event.is_set():
                try:
                    if device_id not in self.cmd_queues:
                        self.cmd_queues[device_id] = asyncio.Queue()
                    
                    await asyncio.gather(
                        self._monitor_loop(device_id),
                        self._control_loop(device_id)
                    )
                except Exception as e:
                    err_msg = str(e)
                    self.manager.log("YANDEX", f"Connection error for {device_id}: {err_msg}", level="error")
                    self.states[device_id]["online"] = False
                    self._broadcast_state()
                    
                    if "4000" in err_msg or "Invalid token" in err_msg:
                        self.manager.log("YANDEX", f"Invalid token detected for {device_id}, refreshing...", level="warning")
                        if self._refresh_tokens_sync():
                            self.manager.log("YANDEX", "Tokens refreshed successfully, retrying connection...", level="info")
                            await asyncio.sleep(2)
                            continue
                    
                    await asyncio.sleep(5)
        
        loop.run_until_complete(run())

    async def _monitor_loop(self, device_id):
        ip = self.devices[device_id].get("ip")
        ssl_ctx = get_ssl_ctx()
        
        async with websockets.connect(f"wss://{ip}:1961", ssl=ssl_ctx, ping_interval=10) as ws:
            self.connections[device_id] = ws
            self.states[device_id]["online"] = True
            self._broadcast_state()
            
            async def heartbeat():
                while True:
                    try:
                        is_forcing = time.time() < self._force_broadcast_until.get(device_id, 0)
                        await asyncio.sleep(0.5 if is_forcing else 3.0)
                        await ws.send(json.dumps({
                            "conversationToken": self.devices[device_id]["glagol_token"],
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
                        if self._internal_parse_state(device_id, data["state"]):
                            self._broadcast_state()
            finally:
                hb_task.cancel()

    async def _control_loop(self, device_id):
        ip = self.devices[device_id].get("ip")
        ssl_ctx = get_ssl_ctx()
        
        async with websockets.connect(f"wss://{ip}:1961", ssl=ssl_ctx, ping_interval=3, ping_timeout=2) as ws:
            try:
                sock = ws.transport.get_extra_info('socket')
                if sock:
                    import socket
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except: pass
            
            self.control_conns[device_id] = ws
            queue = self.cmd_queues[device_id]
            
            async def trash_consumer():
                try:
                    async for _ in ws: pass
                except: pass
            
            tc_task = asyncio.create_task(trash_consumer())
            
            try:
                while True:
                    payload = await queue.get()
                    try:
                        await ws.send(json.dumps(payload))
                    except: break
                    queue.task_done()
            finally:
                tc_task.cancel()

    def _internal_parse_state(self, device_id, s):
        """Обертка над универсальным парсером для отслеживания изменений"""
        new_vals = parse_state(s)
        
        core_changed = False
        core_keys = ["playing", "title", "artist", "volume", "track_id"]
        for k in core_keys:
            if self.states[device_id].get(k) != new_vals.get(k):
                core_changed = True
                break
        
        self.states[device_id].update(new_vals)
        
        now = time.time()
        last_broadcast = getattr(self, "_last_broadcast_time", 0)
        if core_changed or (now - last_broadcast > 5.0) or (device_id in self._force_broadcast_until and now < self._force_broadcast_until[device_id]):
            self._last_broadcast_time = now
            return True
        return False

    async def _request_state(self, device_id):
        if device_id in self.cmd_queues:
            await self.cmd_queues[device_id].put({
                "conversationToken": self.devices[device_id]["glagol_token"],
                "id": str(uuid.uuid4()),
                "sentTime": int(round(time.time() * 1000)),
                "payload": {"command": "getState"}
            })

    def _broadcast_state(self):
        stats = self.get_stats()
        current_hash = json.dumps(stats["devices"], sort_keys=True)
        if current_hash != self._last_state_hash:
            self.manager.broadcast_stats(stats)
            self._last_state_hash = current_hash

    def get_wizard_data(self):
        devices = []
        for d_id, d in self.devices.items():
            devices.append({"id": d_id, "label": d.get("name", d_id), "type": "yandex_station"})
        return {
            "title": "Выбор колонок",
            "description": "Выберите колонки, которыми вы хотите управлять с планшета.",
            "items": devices
        }

    def handle_wizard(self, selections):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                current_config = json.load(f)
        except:
            current_config = {}

        meta = {
            "version": current_config.get("version", "1.1.0"),
            "author_name": current_config.get("author_name", "BlackAlex1"),
            "author_url": current_config.get("author_url", "https://github.com/blackalex1"),
            "id": "yandex_station",
            "name": "Яндекс Станции"
        }
        current_config.update(meta)
        current_config.update({"selected_device_ids": selections, "dependencies": ["pc_media"], "widgets": []})
        
        self.config = current_config
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(current_config, f, indent=2, ensure_ascii=False)

    def get_active_items(self):
        return self.config.get("selected_device_ids", [])

    def handle_command(self, target, action, data=None):
        if target not in self.loops: return
        
        cmd = action
        val = None
        if ":" in action:
            cmd, val = action.split(":", 1)

        payload_data = None
        if cmd == "play_pause":
            is_playing = self.states.get(target, {}).get("playing", False)
            self.states[target]["playing"] = not is_playing
            payload_data = {"command": "stop" if is_playing else "play"}
            self._force_broadcast_until[target] = time.time() + 3.0
            
        elif cmd == "next_track":
            payload_data = {"command": "next"}
            self.states[target].update({"title": "Загрузка...", "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
            
        elif cmd == "prev_track":
            payload_data = {"command": "prev"}
            self.states[target].update({"title": "Загрузка...", "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
            
        elif cmd == "set_volume":
            try:
                v_float = float(val) / 100.0
                payload_data = {"command": "setVolume", "volume": v_float}
                self.states[target]["volume"] = int(float(val))
                self._force_broadcast_until[target] = time.time() + 3.0
            except: pass

        self._broadcast_state()

        loop = self.loops[target]
        full_payload = {
            "conversationToken": self.devices[target]["glagol_token"],
            "id": str(uuid.uuid4()),
            "sentTime": int(round(time.time() * 1000)),
            "payload": payload_data
        }

        loop.call_soon_threadsafe(self.cmd_queues[target].put_nowait, full_payload)
        
        async def delayed_request():
            await asyncio.sleep(0.5)
            await self._request_state(target)
        asyncio.run_coroutine_threadsafe(delayed_request(), loop)

    def get_stats(self):
        allowed_ids = self.config.get("selected_device_ids", [])
        devices_list = []
        for d_id, s in self.states.items():
            if allowed_ids and d_id not in allowed_ids: continue
            devices_list.append({
                "id": d_id, "name": s["name"], "online": s["online"],
                "playing": s["playing"], "volume": s["volume"],
                "title": s["title"], "subtitle": s["artist"],
                "cover": s["cover"], "alice_state": s.get("alice_state", "IDLE"),
                "track_id": s.get("track_id")
            })
        return {"plugin_id": "yandex_station", "devices": devices_list}
