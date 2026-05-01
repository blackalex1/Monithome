import threading
import time
import subprocess
import json
import os
import sys
from comtypes import CoInitialize
from base import BasePlugin

# Импортируем наши новые модули
try:
    from .volume_utils import VolumeManager, press_media_key
except ImportError:
    from volume_utils import VolumeManager, press_media_key

# Виртуальные коды клавиш Windows
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
        super().__init__(socketio, config, manager)
        self.volume_manager = VolumeManager()
        self._media_info = {"title": "", "artist": "", "playing": False, "cover": None, "duration": 0.0, "progress": 0.0, "volume": 0, "mute": False, "status": "initializing"}
        self._data_lock = threading.RLock()
        self._stop_event = threading.Event()
        self.log("PC Media plugin initialized")

    def start(self):
        """Запуск фонового потока мониторинга"""
        self.log("Starting media monitoring worker...")
        threading.Thread(target=self._media_worker, daemon=True).start()

    def stop(self):
        self._stop_event.set()

    def _media_worker(self):
        python_exe = sys.executable
        scanner_path = os.path.join(os.path.dirname(__file__), "media_scanner.py")
        
        while not self._stop_event.is_set():
            try:
                self.log(f"Spawning media scanner: {scanner_path}")
                process = subprocess.Popen(
                    [python_exe, scanner_path],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=False,
                    creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0
                )
                import io
                # Используем TextIOWrapper для надежного чтения UTF-8 и длинных строк
                stdout_reader = io.TextIOWrapper(process.stdout, encoding='utf-8', line_buffering=True)
                
                self.log("Scanner process spawned, waiting for output...")

                try:
                    for line in stdout_reader:
                        if self._stop_event.is_set(): break
                        line = line.strip()
                        if not line: continue
                        try:
                            info = json.loads(line)
                            if "log" in info and len(info) == 1:
                                self.log(f"Scanner: {info['log']}")
                                continue
                            
                            # self.log(f"Received data from scanner: {info.get('title')} - {info.get('artist')}")
                                
                            with self._data_lock:
                                # Если сканер прислал сигнал об обновлении обложки в файле
                                if "cover_event" in info:
                                    self._send_cover_from_file(info.get("title", ""))
                                    continue

                                # Иначе это обычное обновление статов
                                is_new_track = (info.get("title") != self._media_info.get("title") or 
                                               (info.get("artist") is not None and info.get("artist") != self._media_info.get("artist")))
                                
                                # Обновляем все данные
                                self._media_info.update(info)
                                
                                # Рассылаем обновление
                                if is_new_track:
                                    self.log(f"Now playing: {info.get('title')} - {info.get('artist')}")
                                    self.update_state(self.get_stats())
                                else:
                                    self.update_state(self.get_stats())
                                    
                        except Exception as e:
                            self.log(f"Failed to parse scanner line: {line[:100]}... Error: {e}")
                            continue
                except Exception as e:
                    self.log(f"Scanner read error: {e}")
                
                process.terminate()
                self.log("Scanner process terminated, restarting in 2s...")
            except Exception as e:
                self.log(f"Media worker loop error: {str(e)}", level="error")
                import traceback
                self.log(traceback.format_exc(), level="debug")
                time.sleep(2)

    def get_stats(self):
        with self._data_lock:
            # Возвращаем копию данных БЕЗ обложки для MessagePack
            stats = {k: v for k, v in self._media_info.items() if k != "cover"}
            # Убеждаемся что типы верные
            stats["duration"] = float(stats.get("duration", 0.0))
            stats["progress"] = float(stats.get("progress", 0.0))
            stats["volume"] = int(stats.get("volume", 0))
            return stats

    def _send_cover_from_file(self, title):
        """Читает обложку из файла и отправляет в UI"""
        try:
            import base64
            cover_path = os.path.join(os.path.dirname(__file__), "cover.jpg")
            if os.path.exists(cover_path):
                with open(cover_path, "rb") as f:
                    raw_data = f.read()
                    cover_base64 = base64.b64encode(raw_data).decode('utf-8')
                    
                with self._data_lock:
                    self._media_info["cover"] = cover_base64
                    
                self.log(f"Sending cover from file for: {title} ({len(cover_base64)} bytes)")
                self.manager.emit_to_plugin_ui(self.p_id, "cover", {"cover": cover_base64, "title": title})
        except Exception as e:
            self.log(f"Failed to read/send cover file: {e}", level="error")

    def handle_command(self, target, action, data=None):
        if action.startswith("set_volume:"):
            try:
                level = int(action.split(":")[1])
                self.volume_manager.set_volume(level)
            except: pass
        elif action == "toggle_mute":
            # Берем текущее состояние из наших данных
            with self._data_lock:
                current_mute = self._media_info.get("mute", False)
            self.volume_manager.set_mute(not current_mute)
        elif action == "next":
            press_media_key(VK_MEDIA_NEXT_TRACK)
        elif action == "prev":
            press_media_key(VK_MEDIA_PREV_TRACK)
        elif action == "play_pause":
            press_media_key(VK_MEDIA_PLAY_PAUSE)
        
        # Мы не обновляем стейт здесь принудительно, 
        # так как media_scanner заметит изменение громкости и сам пришлет пакет через 0.5с
