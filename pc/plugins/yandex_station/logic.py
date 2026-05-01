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
from base import BasePlugin

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
        super().__init__(socketio, config, manager)
        self._stop_event = threading.Event()
        self.devices = {}
        self.states = {}
        self.connections = {} 
        self.control_conns = {} 
        self.loops = {} 
        self.cmd_queues = {} 
        self._refreshing_tokens = False
        self._last_refresh_time = 0
        self._last_state_hash = ""
        self._force_broadcast_until = {} 
        
        self._load_tokens()

    def _load_tokens(self):
        """Загрузка токенов и инициализация структур данных"""
        if not TOKENS_FILE.exists():
            self.log("Tokens file not found. Please run discovery first.", level="warning")
            return

        with open(TOKENS_FILE, "r", encoding="utf-8") as f:
            self.devices = json.load(f)
        
        # Инициализируем стейты
        for d_id, d in self.devices.items():
            self.states[d_id] = {
                "name": d.get("name", "Колонка"), "platform": d.get("platform"),
                "online": False, "volume": 0, "playing": False,
                "title": "", "artist": "", "cover": "",
                "track_id": "", "alice_state": "IDLE"
            }

        # Если управление на планшете, нам не нужны локальные очереди и циклы
        if self.config.get("tablet_control", False):
            return

        for d_id in self.devices:
            self.cmd_queues[d_id] = asyncio.Queue()
            self._force_broadcast_until[d_id] = 0

    def start(self):
        """Запуск воркеров для каждой колонки или передача конфига планшету"""
        self.manager.subscribe("client_connected", self.on_ui_connected)
        
        if self.config.get("tablet_control", False):
            self.log("==================================================")
            self.log(">>> CONTROL MODE: [ TABLET / STANDALONE ]      <<<")
            self.log(">>> PC local workers are SUSPENDED             <<<")
            self.log("==================================================")
            self._broadcast_config_to_tablet()
            return

        self.log("==================================================")
        self.log(">>> CONTROL MODE: [ PC / LOCAL ]               <<<")
        self.log(f">>> Starting local workers for {len(self.devices)} devices <<<")
        self.log("==================================================")
        for d_id in self.devices:
            name = self.devices[d_id].get("name", d_id)
            self.log(f"Spawning worker for: {name} ({d_id})")
            threading.Thread(target=self._device_worker, args=(d_id,), daemon=True).start()

    def _broadcast_config_to_tablet(self, sid=None):
        """Передача токенов или сигнала об отключении прямого управления"""
        # Извлекаем YANDEX_TOKEN (для музыки/текстов)
        y_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="):
                        y_token = line.split("=")[1].strip()

        configs = []
        # Если управление на планшете - шлем конфиги, иначе шлем пустой список
        if self.config.get("tablet_control", False):
            allowed_ids = self.config.get("selected_device_ids", [])
            for d_id, d in self.devices.items():
                if allowed_ids and d_id not in allowed_ids:
                    continue
                    
                ip = d.get("ip")
                token = d.get("glagol_token")
                name = d.get("name", "Яндекс Станция")
                
                if ip and token:
                    configs.append({
                        "id": d_id,
                        "glagol_token": token,
                        "name": name,
                        "ip": ip
                    })
                else:
                    self.log(f"Skipping device {d_id} in broadcast: missing IP or token", level="warning")
        
        payload = {
            "devices": configs,
            "yandex_token": y_token,
            "enabled": self.config.get("tablet_control", False)
        }
        
        self.manager.emit_to_plugin_ui(self.p_id, "yandex_config", payload, sid=sid)
        self.log(f"Broadcasted standalone state (enabled={payload['enabled']}) with {len(configs)} devices to {sid if sid else 'all'}.")

    def get_initial_events(self):
        """Отправка всех текущих обложек новому клиенту"""
        events = []
        for d_id, state in self.states.items():
            cover = state.get('cover', '')
            title = state.get('title', '')
            if cover:
                events.append({
                    "event": "cover",
                    "data": {
                        "cover": cover,
                        "device_id": d_id,
                        "title": title
                    }
                })
        return events

    def stop(self):
        self.log("Stopping Yandex Station plugin...")
        self._stop_event.set()
        self.manager.unsubscribe("client_connected", self.on_ui_connected)
        for d_id in self.loops:
            self.log(f"Requesting loop stop for device: {d_id}")
            self.loops[d_id].call_soon_threadsafe(self.loops[d_id].stop)
        self.log("Plugin stop sequence initiated.")

    def _refresh_tokens_sync(self):
        now = time.time()
        if self._refreshing_tokens or (now - self._last_refresh_time < 10): return False
        self._refreshing_tokens = True
        self._last_refresh_time = now
        self.log("Starting automatic token refresh cycle...")
        try:
            x_token = None
            if AUTH_FILE.exists():
                with open(AUTH_FILE, "r") as f:
                    for line in f:
                        if line.startswith("YANDEX_TOKEN="):
                            x_token = line.split("=")[1].strip()
            if not x_token: return False
            interfaces = get_all_interfaces()
            zeroconf = Zeroconf(interfaces=interfaces)
            discovery = SpeakerDiscovery()
            browser = ServiceBrowser(zeroconf, "_yandexio._tcp.local.", discovery)
            time.sleep(7)
            browser.cancel()
            zeroconf.close()
            local_devices = discovery.found_devices
            headers = {"Authorization": f"OAuth {x_token}", "X-Yandex-Token": x_token, "User-Agent": "Mozilla/5.0"}
            r = requests.get("https://quasar.yandex.net/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200: r = requests.get("https://quasar.yandex.ru/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200: return False
            quasar_list = r.json().get("devices", [])
            new_results = {}
            for q_dev in quasar_list:
                d_id = q_dev.get("id", "").strip()
                if not d_id: continue
                g_token = q_dev.get("glagol_token") or q_dev.get("glagol", {}).get("token")
                if not g_token:
                    url_single = f"https://quasar.yandex.ru/glagol/token?device_id={d_id}&platform={q_dev.get('platform')}"
                    try:
                        r_s = requests.get(url_single, headers=headers, timeout=5)
                        if r_s.status_code == 200: g_token = r_s.json().get("token")
                    except: pass
                if not g_token: continue
                ip = local_devices.get(d_id, {}).get("ip") or self.devices.get(d_id, {}).get("ip")
                if ip:
                    new_results[d_id] = {"name": q_dev.get("name", self.i18n("speaker")).strip(), "glagol_token": g_token, "platform": q_dev.get("platform"), "ip": ip}
            if new_results:
                with open(TOKENS_FILE, "w", encoding="utf-8") as f: json.dump(new_results, f, ensure_ascii=False, indent=2)
                self.devices = new_results
                return True
        except Exception as e: self.log(f"Auto-refresh exception: {e}", level="error")
        finally: self._refreshing_tokens = False
        return False

    def _device_worker(self, device_id):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self.loops[device_id] = loop
        async def run():
            while not self._stop_event.is_set():
                try:
                    if device_id not in self.cmd_queues: self.cmd_queues[device_id] = asyncio.Queue()
                    await asyncio.gather(self._monitor_loop(device_id), self._control_loop(device_id))
                except Exception as e:
                    self.log(f"Connection error for {device_id}: {e}", level="error")
                    self.states[device_id]["online"] = False
                    self._push_state()
                    if "4000" in str(e) or "Invalid token" in str(e):
                        if self._refresh_tokens_sync():
                            await asyncio.sleep(2)
                            continue
                    await asyncio.sleep(5)
        self.log(f"Worker for {device_id} is starting its loop...")
        try:
            loop.run_until_complete(run())
        except (RuntimeError, asyncio.CancelledError):
            pass 
        finally:
            self.log(f"Worker for {device_id} has stopped.")

    async def _monitor_loop(self, device_id):
        ip = self.devices[device_id].get("ip")
        ssl_ctx = get_ssl_ctx()
        async with websockets.connect(f"wss://{ip}:1961", ssl=ssl_ctx, ping_interval=10) as ws:
            self.connections[device_id] = ws
            self.states[device_id]["online"] = True
            self.log(f"Connected to speaker {device_id} at {ip}")
            self._push_state() # Принудительно шлем статус "Online"
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
                            self._push_state()
            finally: hb_task.cancel()

    async def _control_loop(self, device_id):
        ip = self.devices[device_id].get("ip")
        ssl_ctx = get_ssl_ctx()
        async with websockets.connect(f"wss://{ip}:1961", ssl=ssl_ctx, ping_interval=3, ping_timeout=2) as ws:
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
                    try: await ws.send(json.dumps(payload))
                    except: break
                    queue.task_done()
            finally: tc_task.cancel()

    def _internal_parse_state(self, device_id, s):
        new_vals = parse_state(s)
        core_changed = False
        core_keys = ["playing", "title", "artist", "volume", "track_id", "progress"]
        for k in core_keys:
            if self.states[device_id].get(k) != new_vals.get(k):
                core_changed = True
                break
        
        # if core_changed:
        #    self.log(f"DEBUG: State changed for {device_id}: playing={new_vals.get('playing')}, title={new_vals.get('title')}")

        old_track = self.states[device_id].get("track_id")
        is_new_track = bool(new_vals.get("track_id")) and new_vals.get("track_id") != old_track

        # Сохраняем обложку отдельно, остальные поля обновляем в стейт
        new_cover = new_vals.get("cover", "")
        self.states[device_id].update(new_vals)  # Полное обновление стейта
        self.states[device_id]["last_update"] = time.time()

        if is_new_track:
            self.manager.emit_event("track_changed", {"device_id": device_id, "track_id": new_vals["track_id"]})

        # Отправляем обложку отдельно через JSON (не msgpack), если она изменилась
        old_cover = self.states[device_id].get("_sent_cover", "")
        if new_cover and new_cover != old_cover:
            self.states[device_id]["_sent_cover"] = new_cover
            self.manager.emit_to_plugin_ui(
                self.p_id, "cover",
                {"cover": new_cover, "device_id": device_id, "title": new_vals.get("title", "")}
            )

        now = time.time()
        last_broadcast = getattr(self, "_last_broadcast_time", 0)
        if core_changed or (now - last_broadcast > 5.0) or (device_id in self._force_broadcast_until and now < self._force_broadcast_until[device_id]):
            self._last_broadcast_time = now
            return True
        return False

    def _push_state(self):
        """Отправка текущего состояния в ядро"""
        if self.config.get("tablet_control", False):
            # В режиме прямого управления не шлем динамику (громкость, прогресс)
            # Чтобы не затирать данные на планшете старыми значениями с ПК
            minimal_devices = []
            for d_id, d in self.devices.items():
                if d_id in self.config.get("selected_device_ids", []):
                    minimal_devices.append({
                        "id": d_id,
                        "name": d.get("name"),
                        "status": "direct" # Флаг для планшета
                    })
            self.update_state({"devices": minimal_devices})
            return

        stats = self.get_stats()
        # self.log(f"Pushing state to core: {len(stats['devices'])} devices")
        self.update_state({"devices": stats["devices"]})

    def on_ui_connected(self, sid=None):
        """Вызывается когда планшет подключается к серверу"""
        self.log(f"on_ui_connected triggered for sid: {sid}")
        self._broadcast_config_to_tablet(sid=sid)

    def get_wizard_data(self):
        devices = [{"id": d_id, "label": d.get("name", d_id), "type": "yandex_station"} for d_id, d in self.devices.items()]
        # Добавляем настройку управления с планшета как элемент списка
        settings = [
            {"id": "setting:tablet_control", "label": self.i18n("tablet_control_label"), "type": "setting"}
        ]
        return {
            "title": self.i18n("wizard_title"), 
            "description": self.i18n("wizard_desc"), 
            "items": settings + devices,
            "actions": [
                {"id": "refresh_discovery", "label": self.i18n("refresh_discovery"), "icon": "RefreshCw"}
            ]
        }

    def handle_wizard(self, selections):
        config_path = CONFIG_FILE
        self.log(f"WIZARD DEBUG: Received selections: {selections}")
        
        # Извлекаем настройки из общего списка выделений
        tablet_control = "setting:tablet_control" in selections
        selected_devices = [s for s in selections if not s.startswith("setting:")]
        
        old_mode = self.config.get("tablet_control", False)
        self.log(f"WIZARD DEBUG: old_mode={old_mode}, new_mode={tablet_control}")
        
        # Сохраняем конфиг через базовый метод (он сам запишет на диск и обновит кэш)
        self.save_config({
            "selected_device_ids": selected_devices,
            "tablet_control": tablet_control,
            "dependencies": ["yandex_lyrics"]
        })
        
        mode_str = "TABLET" if tablet_control else "PC"
        if old_mode != tablet_control:
            self.log(f"!!! MODE TOGGLED: {mode_str} !!!")
        else:
            self.log(f"WIZARD: Config updated (mode remains {mode_str})")

        # Перезагружаем плагин, чтобы изменения вступили в силу (остановка/запуск воркеров)
        self.manager.reload_plugin(self.p_id)
        if "yandex_lyrics" in self.manager.plugins:
            self.manager.reload_plugin("yandex_lyrics")

    def get_active_items(self):
        active = list(self.config.get("selected_device_ids") or [])
        if self.config.get("tablet_control", False):
            active.append("setting:tablet_control")
        return active

    def handle_command(self, target, action, data=None):
        # Сначала даем базе обработать общие команды (мастер настройки)
        if super().handle_command(target, action, data):
            return

        if action == "get_yandex_config":
            from flask import request
            self._broadcast_config_to_tablet(sid=request.sid)
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
            self.states[target].update({"title": self.i18n("loading"), "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
        elif cmd == "prev_track":
            payload_data = {"command": "prev"}
            self.states[target].update({"title": self.i18n("loading"), "playing": True})
            self._force_broadcast_until[target] = time.time() + 5.0
        elif cmd == "set_volume":
            try:
                v_float = float(val) / 100.0
                payload_data = {"command": "setVolume", "volume": v_float}
                self.states[target]["volume"] = int(float(val))
                self._force_broadcast_until[target] = time.time() + 3.0
            except: pass
        elif cmd == "refresh_discovery":
            # Принудительный поиск колонок
            self.log("Manual discovery refresh requested")
            self._refresh_tokens_sync()
            self.manager.broadcast_ui()
            return
        elif cmd == "sync_track":
            # Планшет сообщает нам, что трек изменился (при прямом управлении)
            if data and isinstance(data, dict):
                track_id = data.get("track_id")
                if track_id:
                    self.log(f"Synced track from tablet for {target}: {track_id}")
                    self.states[target]["track_id"] = track_id
                    self.manager.emit_event("track_changed", {"device_id": target, "track_id": track_id})
            return # Не отправляем ничего на колонку, это просто синхронизация стейта
        self._push_state()
        full_payload = {
            "conversationToken": self.devices[target]["glagol_token"],
            "id": str(uuid.uuid4()),
            "sentTime": int(round(time.time() * 1000)),
            "payload": payload_data
        }
        self.loops[target].call_soon_threadsafe(self.cmd_queues[target].put_nowait, full_payload)
        async def delayed_request():
            await asyncio.sleep(0.5); await self._monitor_request_state(target)
        asyncio.run_coroutine_threadsafe(delayed_request(), self.loops[target])

    async def _monitor_request_state(self, device_id):
        if device_id in self.cmd_queues:
            await self.cmd_queues[device_id].put({
                "conversationToken": self.devices[device_id]["glagol_token"],
                "id": str(uuid.uuid4()),
                "sentTime": int(round(time.time() * 1000)),
                "payload": {"command": "getState"}
            })

    def get_stats(self):
        allowed_ids = self.config.get("selected_device_ids", [])
        devices_list = []
        for d_id, s in self.states.items():
            if allowed_ids and d_id not in allowed_ids: continue
            devices_list.append({
                "id": d_id, "name": s.get("name", ""), "online": s.get("online", False),
                "playing": s.get("playing", False), "volume": s.get("volume", 0),
                "title": s.get("title", ""), "subtitle": s.get("artist", ""),
                # cover идёт через plugin_event, не msgpack
                "alice_state": s.get("alice_state", "IDLE"),
                "track_id": s.get("track_id", ""),
                "progress": s.get("progress", 0),
                "duration": s.get("duration", 0),
                "last_update": s.get("last_update", time.time())
            })
        return {"devices": devices_list}
