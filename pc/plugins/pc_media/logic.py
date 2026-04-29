import threading
import time
import subprocess
import json
import base64
import os
import sys
import ctypes
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
from comtypes import CLSCTX_ALL, CoInitialize
from ctypes import cast, POINTER

# Виртуальные коды клавиш Windows
VK_MEDIA_NEXT_TRACK = 0xB0
VK_MEDIA_PREV_TRACK = 0xB1
VK_MEDIA_PLAY_PAUSE = 0xB3
VK_VOLUME_UP = 0xAF
VK_VOLUME_DOWN = 0xAE

def press_key(vk_code):
    ctypes.windll.user32.keybd_event(vk_code, 0, 0, 0)
    ctypes.windll.user32.keybd_event(vk_code, 0, 2, 0)

class Plugin:
    def __init__(self, socketio, config, manager):
# ... (оставляем init без изменений, кроме удаления pyautogui)
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._volume = None
        self._audio_initialized = False
        self._media_info = {"title": "", "subtitle": "", "playing": False, "cover": None}
        self._current_volume = 0
        self._current_mute = False
        self._last_sent_image_title = ""
        self._data_lock = threading.RLock() # Используем рекурсивный замок для предотвращения deadlock
        
        CoInitialize()
        self._init_audio()
        
        self._stop_event = threading.Event()
        self._vol_thread = threading.Thread(target=self._volume_monitoring_loop, daemon=True)
        self._vol_thread.start()
        self._media_thread = threading.Thread(target=self._media_worker, daemon=True)
        self._media_thread.start()

    def _init_audio(self):
        try:
            CoInitialize()
            enumerator = AudioUtilities.GetDeviceEnumerator()
            device = enumerator.GetDefaultAudioEndpoint(0, 1)
            if device:
                interface = device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
                self._volume = cast(interface, POINTER(IAudioEndpointVolume))
                self._audio_initialized = True
        except: 
            self._audio_initialized = False

    def _volume_monitoring_loop(self):
        CoInitialize()
        while not self._stop_event.is_set():
            if self._audio_initialized and self._volume:
                try:
                    v = round(self._volume.GetMasterVolumeLevelScalar() * 100)
                    m = self._volume.GetMute()
                    if v != self._current_volume or m != self._current_mute:
                        with self._data_lock:
                            self._current_volume = v
                            self._current_mute = m
                        self.socketio.emit('stats', self.get_stats_lite())
                except:
                    self._audio_initialized = False
                    self._init_audio()
            time.sleep(0.3)

    def get_stats_lite(self):
        """Легкая версия статов для громкости (без обложки)"""
        with self._data_lock:
            return {
                "plugin_id": "pc_media",
                "volume": self._current_volume,
                "mute": self._current_mute,
                "playing": self._media_info["playing"],
                "title": self._media_info["title"],
                "subtitle": self._media_info["subtitle"],
                "cover": None
            }

    def _media_worker(self):
        """Поток для мониторинга медиа через постоянный фоновый процесс"""
        python_exe = sys.executable
        script_path = os.path.join(os.path.dirname(__file__), "get_media_info.py")
        
        # Создаем скрипт, который будет работать в бесконечном цикле (УМНЫЙ: СЖАТИЕ ОПЦИОНАЛЬНО)
        code = """
import asyncio
import json
import base64
import sys
import io
import time
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager
from winsdk.windows.storage.streams import DataReader

# Пробуем импортировать PIL для сжатия, если нет - работаем без него
try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

async def main_loop():
    last_info = {"title": "___INIT___", "subtitle": "", "playing": False}
    manager = await SessionManager.request_async()
    
    while True:
        try:
            # Пытаемся найти сессию, которая реально играет
            sessions = manager.get_sessions()
            session = manager.get_current_session()
            
            # Если текущая сессия не играет, ищем среди всех активных
            if not session or (session.get_playback_info().playback_status != 4):
                for s in sessions:
                    if s.get_playback_info().playback_status == 4:
                        session = s
                        break
            
            # Если так и не нашли играющую, берем хотя бы первую (для отображения названия)
            if not session and sessions:
                session = sessions[0]
            
            if session:
                pb = session.get_playback_info()
                props = await session.try_get_media_properties_async()
                
                title = props.title or ""
                subtitle = props.artist or props.album_artist or ""
                playing = pb.playback_status == 4 if pb else False
                
                if not title and playing:
                    title = "Воспроизведение..."
                
                img = last_info.get("cover")
                # Обновляем обложку только если сменился трек
                if title != last_info.get("title") or subtitle != last_info.get("subtitle"):
                    img = None
                    if props.thumbnail:
                        try:
                            stream = await props.thumbnail.open_read_async()
                            reader = DataReader(stream.get_input_stream_at(0))
                            await reader.load_async(stream.size)
                            raw_data = bytes(reader.read_buffer(stream.size))
                            
                            if HAS_PIL:
                                try:
                                    with Image.open(io.BytesIO(raw_data)) as pill_img:
                                        pill_img.thumbnail((120, 120))
                                        output = io.BytesIO()
                                        pill_img.save(output, format="JPEG", quality=60)
                                        img_data = base64.b64encode(output.getvalue()).decode('utf-8')
                                        img = f"data:image/jpeg;base64,{img_data}"
                                except:
                                    img_data = base64.b64encode(raw_data).decode('utf-8')
                                    img = f"data:image/png;base64,{img_data}"
                            else:
                                img_data = base64.b64encode(raw_data).decode('utf-8')
                                img = f"data:image/png;base64,{img_data}"
                        except: pass
                
                info = {
                    "title": title, "subtitle": subtitle,
                    "playing": playing,
                    "cover": img
                }
                
                # Шлем апдейт если хоть что-то значимое изменилось
                if (info["title"] != last_info.get("title") or 
                    info["subtitle"] != last_info.get("subtitle") or 
                    info["playing"] != last_info.get("playing")):
                    print(json.dumps(info, ensure_ascii=False), flush=True)
                    last_info = info
            else:
                if last_info.get("title") != "":
                    print(json.dumps({"title": "", "subtitle": "", "playing": False, "cover": None}, ensure_ascii=False), flush=True)
                    last_info = {"title": "", "subtitle": "", "playing": False, "cover": None}
        except Exception as e:
            pass
        await asyncio.sleep(0.5)

if __name__ == "__main__":
    asyncio.run(main_loop())
"""
        with open(script_path, "w", encoding="utf-8") as f:
            f.write(code)

        while not self._stop_event.is_set():
            try:
                # Запускаем с флагом -u (unbuffered)
                process = subprocess.Popen(
                    [python_exe, "-u", script_path],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    text=True,
                    encoding='utf-8',
                    bufsize=1
                )
                
                
                for line in process.stdout:
                    if self._stop_event.is_set(): break
                    line = line.strip()
                    if not line: continue
                    
                    try:
                        info = json.loads(line)
                        with self._data_lock:
                            is_new_track = (info["title"] != self._media_info["title"] or 
                                          info["subtitle"] != self._media_info["subtitle"])
                            is_new_state = (info["playing"] != self._media_info["playing"])
                            
                            # Сохраняем обложку, если песня та же
                            if info["cover"] is None and not is_new_track:
                                info["cover"] = self._media_info.get("cover")
                            
                            self._media_info = info
                            
                            if is_new_track:
                                self.manager.log("MEDIA", f"Now playing: {info['title']} - {info['subtitle']}")
                                self.manager.broadcast_stats(self.get_stats())
                            elif is_new_state:
                                self.manager.broadcast_stats(self.get_stats_lite())
                    except: continue
                
                process.terminate()
            except: 
                time.sleep(2)

    def get_stats(self):
        with self._data_lock:
            return {
                "plugin_id": "pc_media",
                "volume": self._current_volume,
                "mute": self._current_mute,
                "playing": self._media_info["playing"],
                "title": self._media_info["title"],
                "subtitle": self._media_info["subtitle"],
                "cover": self._media_info["cover"]
            }

    def get_wizard_data(self):
        """Метаданные для мастера настройки медиа"""
        items = [
            {"id": "pc_control", "label": "Управление музыкой на ПК", "type": "media"}
        ]
        return {
            "title": "Медиа Центр",
            "description": "Настройте единый пульт управления музыкой на ПК и Яндекс Станциях.",
            "items": items
        }

    def handle_wizard(self, selections):
        """Сохранение настроек медиа с защитой метаданных"""
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                current_config = json.load(f)
        except:
            current_config = {}

        # Гарантируем метаданные
        meta = {
            "version": current_config.get("version", "1.1.0"),
            "author_name": current_config.get("author_name", "BlackAlex1"),
            "author_url": current_config.get("author_url", "https://github.com/blackalex1"),
            "description": current_config.get("description", "Управление громкостью и воспроизведением системного плеера Windows."),
            "id": "pc_media",
            "name": "Медиа"
        }

        current_config.update(meta)
        current_config.update({
            "pc_enabled": "pc_control" in selections,
            "widgets": [{
                "id": "unified_media_center",
                "type": "unified_media",
                "label": "Управление Медиа"
            }]
        })
        
        self.config = current_config
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(current_config, f, indent=2, ensure_ascii=False)
        print(f"[MEDIA] Config saved with metadata protection. Version: {current_config.get('version')}")

    def handle_command(self, target, action):
        if action == "get_wizard":
            data = self.get_wizard_data()
            self.manager.emit_to_plugin_ui("pc_media", "wizard_data", data)
            return

        if action.startswith("set_volume:"):
            try:
                val_int = int(action.split(":")[1])
                if self._volume: 
                    self._volume.SetMasterVolumeLevelScalar(val_int / 100.0, None)
                    with self._data_lock:
                        self._current_volume = val_int
            except: pass
        elif action == 'volume_up': press_key(VK_VOLUME_UP)
        elif action == 'volume_down': press_key(VK_VOLUME_DOWN)
        elif action == 'play_pause': 
            press_key(VK_MEDIA_PLAY_PAUSE)
            with self._data_lock:
                self._media_info["playing"] = not self._media_info["playing"]
        elif action == 'next_track': press_key(VK_MEDIA_NEXT_TRACK)
        elif action == 'prev_track': press_key(VK_MEDIA_PREV_TRACK)
        
        # Мгновенный ответ в UI для плавности
        if action.startswith("set_volume:"):
            self.manager.broadcast_stats(self.get_stats_lite())
        else:
            self.manager.broadcast_stats(self.get_stats())

