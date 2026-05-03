import asyncio
import sys
import os
import json
import base64
import traceback
from plugin_engine.base_plugin import BasePlugin
from core.event_bus import event_bus

try:
    from .volume_utils import VolumeManager, press_media_key
except ImportError:
    pass

VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3

class Plugin(BasePlugin):
    """
    Плагин мониторинга PC Media (pc_media v2).
    Использует asyncio.create_subprocess_exec для асинхронного чтения вывода media_scanner.py.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self.volume_manager = VolumeManager()
        self._media_info = {"title": "", "artist": "", "playing": False, "cover": None, "duration": 0.0, "progress": 0.0, "volume": 0, "mute": False, "status": "initializing"}
        self._scanner_task: asyncio.Task | None = None
        self._process = None

    async def on_start(self):
        self.log("pc_media started. Spawning scanner process...")
        self._scanner_task = asyncio.create_task(self._media_worker())

    async def on_stop(self):
        if self._scanner_task:
            self._scanner_task.cancel()
        if self._process:
            try:
                self._process.terminate()
            except: pass
        self.log("pc_media stopped.")

    async def _media_worker(self):
        python_exe = sys.executable
        scanner_path = os.path.join(os.path.dirname(__file__), "media_scanner.py")
        
        while True:
            try:
                self.log(f"Starting subprocess: {scanner_path}")
                self._process = await asyncio.create_subprocess_exec(
                    python_exe, scanner_path,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.STDOUT
                )
                
                while True:
                    line = await self._process.stdout.readline()
                    if not line:
                        break # EOF
                        
                    line_str = line.decode('utf-8', errors='ignore').strip()
                    if not line_str: continue
                    
                    try:
                        info = json.loads(line_str)
                        if "log" in info and len(info) == 1:
                            # self.log(f"Scanner: {info['log']}")
                            continue
                        
                        if "cover_event" in info:
                            await self._send_cover_from_file(info.get("title", ""))
                            continue
                            
                        # Сравниваем, чтобы понять, новый ли это трек
                        is_new_track = (info.get("title") != self._media_info.get("title") or 
                                       (info.get("artist") is not None and info.get("artist") != self._media_info.get("artist")))
                        
                        self._media_info.update(info)
                        
                        if is_new_track:
                            self.log(f"Now playing: {info.get('title')} - {info.get('artist')}")
                        
                        await self._emit_stats()
                        
                    except json.JSONDecodeError:
                        self.log(f"JSON Error: {line_str[:50]}...")
                    except Exception as e:
                        self.log(f"Parse error: {e}")
                        
            except asyncio.CancelledError:
                self.log("Scanner worker cancelled.")
                if self._process:
                    try:
                        self._process.terminate()
                    except: pass
                break
            except Exception as e:
                self.log(f"Scanner error: {e}")
                
            self.log("Scanner process died, restarting in 2s...")
            await asyncio.sleep(2.0)

    async def _emit_stats(self):
        stats = {k: v for k, v in self._media_info.items()}
        stats["duration"] = float(stats.get("duration", 0.0))
        stats["progress"] = float(stats.get("progress", 0.0))
        stats["volume"] = int(stats.get("volume", 0))
        stats["device_name"] = self.i18n("pc_media_device", "Этот компьютер")
        await self.emit_state(stats)

    async def _send_cover_from_file(self, title: str):
        try:
            cover_path = os.path.join(os.path.dirname(__file__), "cover.jpg")
            if os.path.exists(cover_path):
                # Читаем файл в фоне
                def read_file():
                    with open(cover_path, "rb") as f:
                        return base64.b64encode(f.read()).decode('utf-8')
                
                cover_base64 = await asyncio.to_thread(read_file)
                self._media_info["cover"] = cover_base64
                self.log(f"Sending cover for: {title} (size: {len(cover_base64)})")
                await self.emit_event("cover", {"cover": cover_base64, "title": title})
                await self._emit_stats()
        except Exception as e:
            self.log(f"Cover error: {e}", 40)

    async def handle_command(self, action: str, data: any):
        if action == "handle_wizard":
            self.save_config({"pc_enabled": "pc_media_enabled" in data})
            return

        if action.startswith("set_volume:"):
            try:
                level = int(action.split(":")[1])
                await asyncio.to_thread(self.volume_manager.set_volume, level)
            except: pass
        elif action == "toggle_mute":
            current_mute = self._media_info.get("mute", False)
            await asyncio.to_thread(self.volume_manager.set_mute, not current_mute)
        elif action == "next":
            press_media_key(VK_MEDIA_NEXT_TRACK)
        elif action == "prev":
            press_media_key(VK_MEDIA_PREV_TRACK)
        elif action == "play_pause":
            press_media_key(VK_MEDIA_PLAY_PAUSE)
