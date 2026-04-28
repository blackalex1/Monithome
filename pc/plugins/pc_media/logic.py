import pyautogui
import threading
import time
import subprocess
import json
import base64
import os
import sys
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
from comtypes import CLSCTX_ALL, CoInitialize
from ctypes import cast, POINTER

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._volume = None
        self._audio_initialized = False
        self._media_info = {"title": "", "artist": "", "playing": False, "image": None}
        self._current_volume = 0
        self._current_mute = False
        self._last_sent_image_title = ""
        self._data_lock = threading.Lock()
        
        CoInitialize()
        self._init_audio()
        
        self._media_script = os.path.join(os.path.dirname(__file__), "get_media_info.py")
        self._create_helper_script()
        
        self._stop_event = threading.Event()
        self._vol_thread = threading.Thread(target=self._volume_monitoring_loop, daemon=True)
        self._vol_thread.start()
        self._media_thread = threading.Thread(target=self._media_monitoring_loop, daemon=True)
        self._media_thread.start()

    def _create_helper_script(self):
        code = """
import asyncio
import json
import base64
import sys
import io
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager
from winsdk.windows.storage.streams import DataReader

# Принудительно ставим UTF-8 для вывода на Windows
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

async def get_media():
    try:
        manager = await SessionManager.request_async()
        sessions = manager.get_sessions()
        session = manager.get_current_session()
        if not session and sessions: session = sessions[0]
        for s in sessions:
            if s.get_playback_info().playback_status == 4:
                session = s
                break
        if session:
            pb = session.get_playback_info()
            props = await session.try_get_media_properties_async()
            img = None
            if props.thumbnail:
                try:
                    stream = await props.thumbnail.open_read_async()
                    reader = DataReader(stream.get_input_stream_at(0))
                    await reader.load_async(stream.size)
                    buffer = reader.read_buffer(stream.size)
                    img = base64.b64encode(bytes(buffer)).decode('utf-8')
                except: pass
            return {"title": props.title, "artist": props.artist, "playing": pb.playback_status == 4 if pb else False, "image": img}
    except: pass
    return {"title": "", "artist": "", "playing": False, "image": None}

if __name__ == "__main__":
    try:
        result = asyncio.run(get_media())
        print(json.dumps(result, ensure_ascii=False)) # ensure_ascii=False сохраняет кириллицу
    except:
        print(json.dumps({"title": "", "artist": "", "playing": False, "image": None}))
"""
        with open(self._media_script, "w", encoding="utf-8") as f:
            f.write(code)

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
                        self.socketio.emit('stats', {
                            "plugin_id": "pc_media",
                            "volume": self._current_volume,
                            "mute": self._current_mute,
                            "playing": self._media_info["playing"],
                            "title": self._media_info["title"],
                            "artist": self._media_info["artist"],
                            "image": None
                        })
                except:
                    self._audio_initialized = False
                    self._init_audio()
            time.sleep(0.1)

    def _media_monitoring_loop(self):
        CoInitialize()
        python_exe = sys.executable
        while not self._stop_event.is_set():
            try:
                # Читаем вывод строго в UTF-8
                result = subprocess.check_output([python_exe, self._media_script], stderr=subprocess.DEVNULL)
                info = json.loads(result.decode('utf-8'))
                
                if info["title"] != self._media_info["title"] or info["playing"] != self._media_info["playing"]:
                    with self._data_lock:
                        self._media_info = info
                    self.socketio.emit('stats', self.get_stats())
                else:
                    with self._data_lock:
                        self._media_info = info
            except: pass
            time.sleep(0.5)

    def get_stats(self):
        with self._data_lock:
            img_to_send = None
            if self._media_info["title"] != self._last_sent_image_title:
                img_to_send = self._media_info["image"]
                self._last_sent_image_title = self._media_info["title"]

            return {
                "plugin_id": "pc_media",
                "volume": self._current_volume,
                "mute": self._current_mute,
                "playing": self._media_info["playing"],
                "title": self._media_info["title"],
                "artist": self._media_info["artist"],
                "image": img_to_send
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
        """Сохранение настроек медиа"""
        new_config = {
            "id": "pc_media",
            "name": "Медиа",
            "pc_enabled": "pc_control" in selections,
            "widgets": []
        }
        
        # Всегда добавляем ОДИН общий виджет, если хоть что-то выбрано (или просто по умолчанию)
        new_config["widgets"].append({
            "id": "unified_media_center",
            "type": "unified_media",
            "label": "Управление Медиа"
        })
        
        self.config = new_config
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(new_config, f, indent=2, ensure_ascii=False)

    def handle_command(self, target, action):
        if action == "get_wizard":
            data = self.get_wizard_data()
            self.socketio.emit('wizard_data', {
                "plugin_id": "pc_media", 
                "wizard": data,
                "plugin_info": {"id": "pc_media", "config": self.config}
            })
            return

        if action.startswith("set_volume:"):
            try:
                val_int = int(action.split(":")[1])
                if self._volume: 
                    self._volume.SetMasterVolumeLevelScalar(val_int / 100.0, None)
                    with self._data_lock:
                        self._current_volume = val_int
            except: pass
        elif action == 'volume_up': pyautogui.press('volumeup')
        elif action == 'volume_down': pyautogui.press('volumedown')
        elif action == 'play_pause': 
            pyautogui.press('playpause')
            with self._data_lock:
                self._media_info["playing"] = not self._media_info["playing"]
        elif action == 'next_track': pyautogui.press('nexttrack')
        elif action == 'prev_track': pyautogui.press('prevtrack')
        
        if not action.startswith("set_volume:"):
            self.socketio.emit('stats', self.get_stats())

