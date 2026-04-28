import asyncio
import json
import os
import ssl
import time
import uuid
import threading
import websockets
from pathlib import Path

import logging
import requests
import asyncio
import ipaddress
from zeroconf import ServiceBrowser, Zeroconf

# Настройка логирования (только ошибки)
logging.basicConfig(level=logging.ERROR, format='[%(asctime)s] %(message)s')
logger = logging.getLogger("YANDEX")

# Определяем пути относительно файла плагина
PLUGIN_DIR = Path(__file__).parent
TOKENS_FILE = PLUGIN_DIR / "glagol_tokens.json"
AUTH_FILE = PLUGIN_DIR / ".env"

class SpeakerDiscovery:
    def __init__(self):
        self.found_devices = {}
    def remove_service(self, zeroconf, type, name): pass
    def update_service(self, zeroconf, type, name): pass
    def add_service(self, zeroconf, type, name):
        info = zeroconf.get_service_info(type, name)
        if info:
            addresses = [str(ipaddress.ip_address(addr)) for addr in info.addresses]
            props = {k.decode(): v.decode() if isinstance(v, bytes) else v for k, v in info.properties.items()}
            device_id = props.get("deviceId")
            if device_id:
                self.found_devices[device_id] = {"ip": addresses[0], "platform": props.get("platform"), "name": name.split(".")[0]}

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._stop_event = threading.Event()
        self.devices = {}
        self.states = {}
        self.connections = {} 
        self.loops = {} 
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
                print(f"[YANDEX] Error loading tokens: {e}")

    def _refresh_tokens_sync(self):
        """Автоматическое обновление токенов без участия пользователя"""
        if self._refreshing_tokens or (time.time() - self._last_refresh_time < 60):
            return False # Не частим
        
        self._refreshing_tokens = True
        self._last_refresh_time = time.time()
        print("[YANDEX] Triggering automatic token refresh...")
        
        try:
            # 1. Получаем X-Token из .env
            x_token = None
            if AUTH_FILE.exists():
                with open(AUTH_FILE, "r") as f:
                    for line in f:
                        if line.startswith("YANDEX_TOKEN="):
                            x_token = line.split("=")[1].strip()
            
            if not x_token:
                print("[YANDEX] Refresh failed: No YANDEX_TOKEN in .env")
                return False

            # 2. Ищем колонки в сети (mDNS)
            zeroconf = Zeroconf()
            discovery = SpeakerDiscovery()
            browser = ServiceBrowser(zeroconf, "_yandexio._tcp.local.", discovery)
            time.sleep(5)
            zeroconf.close()
            local_devices = discovery.found_devices

            # 3. Запрашиваем новые Glagol-токены у Яндекса
            headers = {"Authorization": f"OAuth {x_token}", "X-Yandex-Token": x_token}
            r = requests.get("https://quasar.yandex.ru/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200: return False
            
            quasar_list = r.json().get("devices", [])
            new_results = {}
            for q_dev in quasar_list:
                d_id = q_dev.get("id")
                g_token = q_dev.get("glagol_token")
                if g_token and d_id in local_devices:
                    new_results[d_id] = {
                        "name": q_dev.get("name", "Колонка").strip(),
                        "glagol_token": g_token,
                        "platform": q_dev.get("platform"),
                        "ip": local_devices[d_id]["ip"]
                    }

            if new_results:
                with open(TOKENS_FILE, "w", encoding="utf-8") as f:
                    json.dump(new_results, f, ensure_ascii=False, indent=2)
                self.devices = new_results
                print(f"[YANDEX] Successfully refreshed tokens for {len(new_results)} devices.")
                return True
        except Exception as e:
            print(f"[YANDEX] Auto-refresh error: {e}")
        finally:
            self._refreshing_tokens = False
        return False

    def _device_worker(self, device_id):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self.loops[device_id] = loop
        
        while not self._stop_event.is_set():
            try:
                loop.run_until_complete(self._maintain_connection(device_id))
            except Exception as e:
                err_str = str(e)
                print(f"[YANDEX] Connection error for {device_id}: {err_str}")
                
                # Если ошибка токена (код 4000 или текст 'Invalid token')
                if "4000" in err_str or "Invalid token" in err_str:
                    print(f"[YANDEX] Invalid token detected for {device_id}. Attempting auto-refresh...")
                    if self._refresh_tokens_sync():
                        # Если токены обновились, подождем немного и попробуем снова
                        time.sleep(1)
                        continue 

                self.states[device_id]["online"] = False
                self._broadcast_state()
            
            time.sleep(5)

    async def _maintain_connection(self, device_id):
        device = self.devices[device_id]
        ip = device.get("ip")
        if not ip: return

        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ssl_context.check_hostname = False
        ssl_context.verify_mode = ssl.CERT_NONE

        logger.info(f"Connecting to {device_id} at {ip}...")
        async with websockets.connect(
            f"wss://{ip}:1961", 
            ssl=ssl_context, 
            ping_interval=None,
            compression=None
        ) as ws:
            self.connections[device_id] = ws
            self.states[device_id]["online"] = True
            
            # Фоновая задача для периодического запроса состояния
            async def heartbeat():
                while not self._stop_event.is_set():
                    try:
                        await self._request_state(device_id)
                        await asyncio.sleep(0.5)
                    except: break

            hb_task = asyncio.create_task(heartbeat())

            try:
                async for message in ws:
                    data = json.loads(message)
                    if "state" in data:
                        should_force = time.time() < self._force_broadcast_until.get(device_id, 0)
                        if self._parse_state(device_id, data["state"]) or should_force:
                            self._broadcast_state()
            finally:
                hb_task.cancel()

    async def _request_state(self, device_id):
        ws = self.connections.get(device_id)
        if ws:
            await ws.send(json.dumps({
                "conversationToken": self.devices[device_id]["glagol_token"],
                "id": str(uuid.uuid4()),
                "sentTime": int(round(time.time() * 1000)),
                "payload": {"command": "getState"}
            }))

    def _parse_state(self, device_id, s):
        p = s.get("playerState", {})
        extra = p.get("extra", {})
        
        title = p.get("title") or extra.get("title") or ""
        artist = p.get("subtitle") or extra.get("artist") or ""
        
        cover = ""
        cover_raw = extra.get("coverURI")
        if cover_raw:
            cover = "https://" + cover_raw.replace("%%", "400x400")

        new_vals = {
            "volume": round(s.get("volume", 0) * 100),
            "playing": s.get("playing", False),
            "title": title,
            "artist": artist,
            "cover": cover,
            "alice_state": s.get("aliceState", "IDLE")
        }
        
        changed = False
        for k, v in new_vals.items():
            if self.states[device_id].get(k) != v:
                changed = True
                break
        
        if changed:
            self.states[device_id].update(new_vals)
        return changed

    def _broadcast_state(self):
        stats = self.get_stats()
        current_hash = json.dumps(stats["devices"], sort_keys=True)
        if current_hash != self._last_state_hash:
            logger.info(f"Broadcasting new state for {len(stats['devices'])} devices")
            self.socketio.emit('stats', stats)
            self._last_state_hash = current_hash

    def get_wizard_data(self):
        """Метаданные для мастера настройки колонок"""
        devices = []
        for d_id, d in self.devices.items():
            devices.append({
                "id": d_id,
                "label": d.get("name", d_id),
                "type": "yandex_station"
            })
        return {
            "title": "Выбор колонок",
            "description": "Выберите колонки, которыми вы хотите управлять с планшета.",
            "items": devices
        }

    def handle_wizard(self, selections):
        """Сохранение выбранных колонок (теперь только данные, без виджетов)"""
        config_path = PLUGIN_DIR / "config.json"
        
        # Загружаем текущий конфиг, чтобы не потерять версию и автора
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                current_config = json.load(f)
        except:
            current_config = {}

        current_config.update({
            "selected_device_ids": selections,
            "dependencies": ["pc_media"],
            "widgets": []
        })
        
        self.config = current_config
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(current_config, f, indent=2, ensure_ascii=False)

    def handle_command(self, target, action, data=None):
        if action == "get_wizard":
            data = self.get_wizard_data()
            self.socketio.emit('wizard_data', {
                "plugin_id": "yandex_station", 
                "wizard": data,
                "plugin_info": {
                    "id": "yandex_station", 
                    "config": self.config,
                    "dependencies": ["pc_media"] # Объявляем зависимость универсально
                }
            })
            return

        if target not in self.connections or target not in self.loops: return
        
        cmd = action
        val = None
        if ":" in action:
            cmd, val = action.split(":", 1)

        payload_data = {}
        if cmd == "next_track": payload_data = {"command": "next"}
        elif cmd == "prev_track": payload_data = {"command": "prev"}
        elif cmd == "play_pause":
            is_playing = self.states.get(target, {}).get("playing", False)
            payload_data = {"command": "stop" if is_playing else "play"}
        elif cmd == "set_volume": 
            payload_data = {"command": "setVolume", "volume": float(val) / 100.0}
        elif cmd == "voice":
            payload_data = {"command": "sendText", "text": str(val)}

        if not payload_data: return

        ws = self.connections[target]
        loop = self.loops[target]
        device = self.devices[target]
        
        # Сбрасываем локальное состояние для принудительного обновления UI
        if cmd in ["next_track", "prev_track"]:
            logger.info(f"Command {cmd} received, resetting state and forcing broadcast...")
            self.states[target].update({"title": "Загрузка...", "artist": "—"})
            self._force_broadcast_until[target] = time.time() + 5.0 # Форсим 5 секунд
            self._broadcast_state()

        full_payload = {
            "conversationToken": device["glagol_token"],
            "id": str(uuid.uuid4()),
            "sentTime": int(round(time.time() * 1000)),
            "payload": payload_data
        }

        # Отправляе команду
        asyncio.run_coroutine_threadsafe(ws.send(json.dumps(full_payload)), loop)
        
        # МГНОВЕННО запрашиваем обновленное состояние после команды
        asyncio.run_coroutine_threadsafe(self._request_state(target), loop)

    def get_stats(self):
        allowed_ids = self.config.get("selected_device_ids", [])
        
        devices_list = []
        for d_id, s in self.states.items():
            # Если пользователь не выбрал эту колонку — не шлем её данные вообще
            if allowed_ids and d_id not in allowed_ids:
                continue
                
            devices_list.append({
                "id": d_id, "name": s["name"], "online": s["online"],
                "playing": s["playing"], "volume": s["volume"],
                "title": s["title"], "subtitle": s["artist"],
                "cover": s["cover"], "alice_state": s.get("alice_state", "IDLE")
            })
        return {"plugin_id": "yandex_station", "devices": devices_list}

