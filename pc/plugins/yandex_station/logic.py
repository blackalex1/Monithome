import asyncio
import json
import time
import uuid
import threading
from .const import TOKENS_FILE, AUTH_FILE, CONFIG_FILE
from .auth import YandexAuth
from .worker import DeviceWorker, monitor_request_state
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
        
        self._qr_url = None
        self._qr_image_base64 = None
        self._qr_status = "idle" # idle, getting_url, waiting, success, error
        
        self.auth = YandexAuth(self)
        self._load_tokens()

    def _load_tokens(self):
        """Загрузка токенов и инициализация структур данных"""
        if not TOKENS_FILE.exists():
            if self.auth.has_token():
                self.log("Tokens file not found. Auto-refreshing...", level="info")
                threading.Thread(target=self.auth.refresh_tokens_sync, daemon=True).start()
            else:
                self.log("Tokens and Yandex token missing. Please login first.", level="warning")
            return

        try:
            with open(TOKENS_FILE, "r", encoding="utf-8") as f:
                self.devices = json.load(f)
        except Exception as e:
            self.log(f"Error loading tokens: {e}", level="error")
            return
        
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
        
        # Проверяем наличие токена Яндекса
        if not self.auth.has_token():
            self.log("Yandex token missing. Starting QR login flow...", level="warning")
            self.auth.start_qr_login()

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
            worker = DeviceWorker(self, d_id)
            threading.Thread(target=worker.start, daemon=True).start()

    def stop(self):
        self.log("Stopping Yandex Station plugin...")
        self._stop_event.set()
        self.manager.unsubscribe("client_connected", self.on_ui_connected)
        for d_id in self.loops:
            self.log(f"Requesting loop stop for device: {d_id}")
            self.loops[d_id].call_soon_threadsafe(self.loops[d_id].stop)
        self.log("Plugin stop sequence initiated.")

    def get_wizard_data(self):
        devices = [{"id": d_id, "label": d.get("name", d_id), "type": "yandex_station"} for d_id, d in self.devices.items()]
        settings = [
            {"id": "setting:tablet_control", "label": self.i18n("tablet_control_label"), "type": "setting"}
        ]

        # Добавляем кнопку авторизации в настройки, если токена нет
        if not self.auth.has_token():
            settings.append({"id": "action:start_qr_login", "label": self.i18n("login_yandex"), "type": "button"})
        
        return {
            "title": self.i18n("wizard_title"), 
            "description": self.i18n("wizard_desc"), 
            "items": settings + devices,
            "actions": [
                {"id": "refresh_discovery", "label": self.i18n("refresh_discovery"), "icon": "RefreshCw"}
            ]
        }

    def handle_wizard(self, selections):
        tablet_control = "setting:tablet_control" in selections
        selected_devices = [s for s in selections if not s.startswith("setting:")]
        
        self.save_config({
            "selected_device_ids": selected_devices,
            "tablet_control": tablet_control
        })
        
        self.manager.reload_plugin(self.p_id)
        if "yandex_lyrics" in self.manager.plugins:
            self.manager.reload_plugin("yandex_lyrics")

    def handle_command(self, sid, target, action, data=None):
        if super().handle_command(sid, target, action, data):
            return

        if action == "get_yandex_config":
            self._broadcast_config_to_tablet(sid=sid)
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
            self.log("Manual discovery refresh requested")
            threading.Thread(target=self.auth.refresh_tokens_sync, daemon=True).start()
            self.manager.broadcast_ui()
            return
        elif cmd == "sync_track":
            if data and isinstance(data, dict):
                track_id = data.get("track_id")
                if track_id:
                    self.states[target]["track_id"] = track_id
                    self.manager.emit_event("track_changed", {"device_id": target, "track_id": track_id})
            return
        elif action == "start_qr_login":
            self.auth.start_qr_login()
            # Сразу уведомляем UI, что начинаем процесс
            self._emit_qr_status()
            return
            
        self._push_state()
        full_payload = {
            "conversationToken": self.devices[target]["glagol_token"],
            "id": str(uuid.uuid4()),
            "sentTime": int(round(time.time() * 1000)),
            "payload": payload_data
        }
        self.loops[target].call_soon_threadsafe(self.cmd_queues[target].put_nowait, full_payload)
        
        async def delayed_request():
            await asyncio.sleep(0.5)
            await monitor_request_state(self, target)
        asyncio.run_coroutine_threadsafe(delayed_request(), self.loops[target])

    def get_stats(self):
        allowed_ids = self.config.get("selected_device_ids", [])
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
        return {"devices": devices_list}

    def _push_state(self):
        if self.config.get("tablet_control", False):
            minimal_devices = []
            for d_id, d in self.devices.items():
                if d_id in self.config.get("selected_device_ids", []):
                    minimal_devices.append({"id": d_id, "name": d.get("name"), "status": "direct"})
            self.update_state({"devices": minimal_devices})
            return
        self.update_state({"devices": self.get_stats()["devices"]})

    def _broadcast_config_to_tablet(self, sid=None):
        y_token = None
        if AUTH_FILE.exists():
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="):
                        y_token = line.split("=")[1].strip()

        configs = []
        if self.config.get("tablet_control", False):
            allowed_ids = self.config.get("selected_device_ids", [])
            for d_id, d in self.devices.items():
                if allowed_ids and d_id not in allowed_ids: continue
                if d.get("ip") and d.get("glagol_token"):
                    configs.append({"id": d_id, "glagol_token": d["glagol_token"], "name": d.get("name"), "ip": d["ip"]})
        
        self.manager.emit_to_plugin_ui(self.p_id, "yandex_config", {"devices": configs, "yandex_token": y_token, "enabled": self.config.get("tablet_control", False)}, sid=sid)

    def on_ui_connected(self, sid=None):
        self._broadcast_config_to_tablet(sid=sid)

    def get_initial_events(self):
        events = []
        for d_id, state in self.states.items():
            if state.get('cover'):
                events.append({"event": "cover", "data": {"cover": state['cover'], "device_id": d_id, "title": state.get('title', '')}})
        return events

    def _emit_qr_status(self):
        """Отправка текущего состояния QR-авторизации в отдельное окно UI"""
        if self._qr_status == "idle": return

        status_text = self.i18n("qr_waiting")
        if self._qr_status == "getting_url": status_text = self.i18n("loading")
        elif self._qr_status == "success": status_text = self.i18n("qr_success")
        elif self._qr_status == "error": status_text = self.i18n("qr_error")

        data = {
            "qr_url": self._qr_image_base64,
            "status": status_text,
            "instructions": self.i18n("scan_qr")
        }
        self.manager.emit_to_plugin_ui(self.p_id, "show_qr", data)

    def get_active_items(self):
        active = list(self.config.get("selected_device_ids") or [])
        if self.config.get("tablet_control", False): active.append("setting:tablet_control")
        return active
