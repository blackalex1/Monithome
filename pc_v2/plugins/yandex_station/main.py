import asyncio
import json
import time
import uuid
import os
from plugin_engine.base_plugin import BasePlugin
from .const import AUTH_FILE
from .auth import YandexAuth
from .worker import DeviceWorker, monitor_request_state
from core.event_bus import event_bus
from zeroconf import ServiceBrowser, Zeroconf
from .discovery import SpeakerDiscovery

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
        self.zeroconf = None
        self.discovery = SpeakerDiscovery()

    async def on_start(self):
        self.loop = asyncio.get_running_loop()
        self._load_tokens()
        
        if not self.auth.has_token():
            self.log("Yandex token missing. Starting QR login flow...", 30) # WARNING
            asyncio.create_task(self.auth.start_qr_login())

        # Запускаем mDNS поиск для обновления IP адресов
        from .discovery import get_all_interfaces
        interfaces = get_all_interfaces()
        self.log(f"Initializing Zeroconf on interfaces: {interfaces}")
        try:
            self.zeroconf = Zeroconf(interfaces=interfaces)
            self.browser = ServiceBrowser(self.zeroconf, "_yandexio._tcp.local.", self)
        except Exception as e:
            self.log(f"Failed to initialize Zeroconf: {e}", 40)
        
        # Подписываемся на изменения конфига, чтобы реагировать на смену режима (ПК/Планшет)
        event_bus.subscribe("ui_config_changed", self._on_config_changed)
        
        await self.apply_mode()

    async def _on_config_changed(self, payload: dict):
        # Если изменился наш плагин или конфиг в целом
        if payload.get("plugin_id") == self.plugin_id or payload.get("plugin_id") is None:
            sid = payload.get("sid")
            self.log(f"Config changed or new client {sid}. Re-applying mode...")
            await self.apply_mode(sid)

    async def apply_mode(self, sid: str = None):
        """Применяет текущий режим управления (Локально / Планшет)"""
        config = self.get_config()
        is_tablet = config.get("tablet_control", False)
        
        mode_str = "STANDALONE (Tablet)" if is_tablet else "PC CONTROL"
        self.log(f"Mode applied: {mode_str}", 20)

        env = self.auth._read_env()
        env_selected = env.get("SELECTED_DEVICES", "")
        selected_ids = [s.strip() for s in env_selected.split(",") if s.strip()]
        if not selected_ids:
            selected_ids = config.get("selected_device_ids", [])

        # УПРАВЛЕНИЕ ВОРКЕРАМИ
        if is_tablet:
            # Если управление у планшета - серверу не нужно держать соединения
            for d_id in list(self.workers.keys()):
                self.log(f"Stopping worker (Standalone mode active): {d_id}")
                self.workers[d_id].stop()
                del self.workers[d_id]
                if d_id in self.cmd_queues: del self.cmd_queues[d_id]
        else:
            # Режим управления с ПК - запускаем воркеры для выбранных устройств
            for d_id in list(self.workers.keys()):
                if selected_ids and d_id not in selected_ids:
                    self.log(f"Stopping worker for unselected device: {d_id}")
                    self.workers[d_id].stop()
                    del self.workers[d_id]
                    if d_id in self.cmd_queues: del self.cmd_queues[d_id]

            for d_id in selected_ids:
                if d_id in self.devices and d_id not in self.workers:
                    self.log(f"Starting worker for: {d_id}")
                    self.cmd_queues[d_id] = asyncio.Queue()
                    self._force_broadcast_until[d_id] = 0
                    worker = DeviceWorker(self, d_id)
                    self.workers[d_id] = worker
                    worker.start()

        # Если это новый клиент (есть sid), даем ему 0.5 сек на подписку
        if sid:
            async def delayed_broadcast():
                await asyncio.sleep(0.5)
                await self._broadcast_config_to_tablet(sid)
            asyncio.create_task(delayed_broadcast())
        else:
            await self._broadcast_config_to_tablet()

        if not is_tablet:
            # Если управление перешло к ПК - принудительно уведомляем другие плагины (например, лирику)
            # о текущих треках, чтобы они подгрузили данные сразу, не дожидаясь смены песни.
            for d_id, state in self.states.items():
                track_id = state.get("track_id")
                if track_id:
                    self.log(f"Forcing track_changed event for {d_id} on mode switch")
                    await self.emit_event("track_changed", {"device_id": d_id, "track_id": track_id})
        
        await self._push_state()

    async def on_stop(self):
        self.log("Stopping Yandex Station plugin...")
        if self.zeroconf:
            self.zeroconf.close()
        for d_id, worker in self.workers.items():
            worker.stop()

    def _load_tokens(self):
        env = self.auth._read_env()
        raw_glagol = env.get("GLAGOL_TOKENS")
        
        if not raw_glagol:
            if self.auth.has_token():
                self.log("Glagol tokens missing in .env. Auto-refreshing...", 20)
                asyncio.create_task(self.auth.refresh_tokens_sync())
            return

        try:
            self.devices = json.loads(raw_glagol)
        except Exception as e:
            self.log(f"Error parsing glagol tokens from .env: {e}", 40)
            return
        
        cached_ips = self.get_config().get("cached_ips", {})
        for d_id, d in self.devices.items():
            if d_id in cached_ips:
                d["ip"] = cached_ips[d_id]
            
            self.states[d_id] = {
                "name": d.get("name", "Колонка"), "platform": d.get("platform"),
                "online": False, "volume": 0, "playing": False,
                "title": "", "artist": "", "cover": "",
                "track_id": "", "alice_state": "IDLE"
            }

    # --- mDNS Handlers ---
    def add_service(self, zc, type, name):
        info = zc.get_service_info(type, name)
        if info:
            import ipaddress
            addresses = [str(ipaddress.ip_address(addr)) for addr in info.addresses if len(addr) == 4]
            props = {k.decode(): v.decode() if isinstance(v, bytes) else v for k, v in info.properties.items()}
            d_id = props.get("deviceId")
            if d_id and addresses:
                ip = addresses[0]
                if d_id in self.devices:
                    if self.devices[d_id].get("ip") != ip:
                        self.log(f"mDNS: Updated IP for {d_id} ({self.devices[d_id].get('name')}): {ip}", 20)
                        self.devices[d_id]["ip"] = ip
                        # Сохраняем IP в кэш конфига для быстрых перезапусков
                        cached_ips = self.get_config().get("cached_ips", {})
                        cached_ips[d_id] = ip
                        self.update_config({"cached_ips": cached_ips})
                        # Переотправляем конфиг планшету, так как появился IP
                        asyncio.run_coroutine_threadsafe(self._broadcast_config_to_tablet(), self.loop)

    def update_service(self, zc, type, name):
        self.add_service(zc, type, name)

    def remove_service(self, zc, type, name):
        pass

    async def handle_command(self, action: str, data: any):
        if action == "get_yandex_config":
            sid = data.get("sid") if isinstance(data, dict) else None
            await self._broadcast_config_to_tablet(sid)
            return
        elif action == "get_wizard_data":
            config = self.get_config()
            devices_list = []
            for d_id, d in self.devices.items():
                devices_list.append({"id": d_id, "name": d.get("name", d_id)})
            
            env = self.auth._read_env()
            env_selected = env.get("SELECTED_DEVICES", "")
            selected_ids = [s.strip() for s in env_selected.split(",") if s.strip()]
            if not selected_ids:
                selected_ids = config.get("selected_device_ids", [])

            await self.emit_event("wizard_data", {
                "devices": devices_list,
                "tablet_control": config.get("tablet_control", False),
                "selected_device_ids": selected_ids
            })
            return
        elif action == "handle_wizard":
            # Сохранение настроек мастера
            self.save_config({
                "tablet_control": data.get("tablet_control", False)
            })
            # Сохраняем выбранные ID в .env
            ids = data.get("selected_device_ids", [])
            self.auth._write_env("SELECTED_DEVICES", ",".join(ids))
            
            # Мгновенно применяем новый режим
            await self.apply_mode()
            return
        elif action == "start_qr_login":
            asyncio.create_task(self.auth.start_qr_login())
            return
            
        if not isinstance(data, dict):
            data = {}

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
        env = self.auth._read_env()
        env_selected = env.get("SELECTED_DEVICES", "")
        allowed_ids = [s.strip() for s in env_selected.split(",") if s.strip()]
        if not allowed_ids:
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
                "artist": s.get("artist", ""),
                "alice_state": s.get("alice_state", "IDLE"),
                "track_id": s.get("track_id", ""),
                "cover": s.get("cover", ""),
                "progress": s.get("progress", 0),
                "duration": s.get("duration", 0),
                "last_update": s.get("last_update", time.time())
            })
        await self.emit_state({"devices": devices_list})
        if devices_list:
            covers_found = sum(1 for d in devices_list if d.get("cover"))
            self.log(f"Pushed state for {len(devices_list)} devices. Covers found: {covers_found}")

    async def _broadcast_config_to_tablet(self, sid=None):
        config = self.get_config()
        print(f"[YandexStation] Sending config to {'all' if not sid else sid}. Standalone: {config.get('tablet_control', False)}")
        
        env = self.auth._read_env()
        y_token = env.get("YANDEX_TOKEN")

        configs = []
        if config.get("tablet_control", False):
            self.log("Broadcast: Preparing Glagol API tokens for Tablet Standalone mode", 20)
            env = self.auth._read_env()
            env_selected = env.get("SELECTED_DEVICES", "")
            allowed_ids = [s.strip() for s in env_selected.split(",") if s.strip()]
            if not allowed_ids:
                allowed_ids = config.get("selected_device_ids", [])

            for d_id, d in self.devices.items():
                if allowed_ids and d_id not in allowed_ids: continue
                
                if d.get("glagol_token"):
                    self.log(f"Broadcast: Adding device {d_id} ({d.get('name')}) to config. IP: {d.get('ip') or 'mDNS pending'}", 20)
                    configs.append({
                        "id": d_id, 
                        "glagol_token": d["glagol_token"], 
                        "name": d.get("name", "Яндекс Станция"), 
                        "ip": d.get("ip"),
                        "port": d.get("port", 1961)
                    })
        
        await self.emit_event("yandex_config", {
            "devices": configs, 
            "yandex_token": y_token, 
            "enabled": config.get("tablet_control", False)
        }, room=sid)
        if sid:
            self.log(f"Broadcast: Config sent to client {sid}. Devices: {len(configs)}", 20)
        else:
            self.log(f"Broadcast: Config sent to all clients. Devices: {len(configs)}", 20)
