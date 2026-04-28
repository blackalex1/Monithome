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

# Настройка логирования (только ошибки)
logging.basicConfig(level=logging.ERROR, format='[%(asctime)s] %(message)s')
logger = logging.getLogger("YANDEX")

# Определяем пути относительно файла плагина
PLUGIN_DIR = Path(__file__).parent
TOKENS_FILE = PLUGIN_DIR / "glagol_tokens.json"

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

    def _device_worker(self, device_id):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self.loops[device_id] = loop
        
        while not self._stop_event.is_set():
            try:
                loop.run_until_complete(self._maintain_connection(device_id))
            except Exception as e:
                print(f"[YANDEX] Connection error for {device_id}: {e}")
                self.states[device_id]["online"] = False
                self._broadcast_state()
            
            time.sleep(2)

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
        new_config = {
            "id": "yandex_station",
            "name": "Яндекс Станции",
            "selected_device_ids": selections,
            "dependencies": ["pc_media"], # Универсальная декларация зависимости
            "widgets": [] # Теперь виджеты здесь не нужны, их создает pc_media
        }
        
        self.config = new_config
        config_path = PLUGIN_DIR / "config.json"
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(new_config, f, indent=2, ensure_ascii=False)

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

