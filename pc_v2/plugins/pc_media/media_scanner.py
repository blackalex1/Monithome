import asyncio
import json
import base64
import sys
import time
import os
from datetime import datetime, timezone
import threading
import ctypes

# КРИТИЧНО: Форсируем MTA (Multi-Threaded Apartment) через comtypes
try:
    import comtypes
    try: comtypes.CoUninitialize()
    except: pass
    comtypes.CoInitializeEx(0) # 0 = COINIT_MULTITHREADED
    print(json.dumps({"log": "COM initialized as MTA (Multi-Threaded)"}), flush=True)
except Exception as e:
    try:
        ctypes.windll.ole32.CoInitializeEx(None, 0x0)
        print(json.dumps({"log": "COM initialized as MTA via ctypes"}), flush=True)
    except:
        pass

try:
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
except Exception as e:
    pass

print(json.dumps({"log": "Media Scanner process started"}), flush=True)

try:
    from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager
    from winsdk.windows.storage.streams import DataReader
    print(json.dumps({"log": "Winsdk modules imported"}), flush=True)
except Exception as e:
    print(json.dumps({"log": f"Winsdk import failed: {repr(e)}"}), flush=True)
    sys.exit(1)

try:
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume, IAudioEndpointVolumeCallback
    from comtypes import CLSCTX_ALL, COMObject
    from ctypes import cast, POINTER, c_float
    HAS_PYCAW = True
except Exception as e:
    print(json.dumps({"log": f"Pycaw import failed: {e}"}), flush=True)
    HAS_PYCAW = False

class VolumeEvents(COMObject):
    _com_interfaces_ = [IAudioEndpointVolumeCallback]

    def __init__(self, callback):
        super(VolumeEvents, self).__init__()
        self.callback = callback

    def OnNotify(self, pNotify):
        # Вызывается Windows при изменении громкости
        data = pNotify.contents
        vol = int(round(data.fMasterVolume * 100))
        mute = data.bMuted == 1
        self.callback(vol, mute)

class SystemVolume:
    def __init__(self, on_change_callback):
        self._volume = None
        self._callback_obj = None
        self.on_change_callback = on_change_callback
        if HAS_PYCAW:
            self.init_audio()

    def init_audio(self):
        try:
            enumerator = AudioUtilities.GetDeviceEnumerator()
            device = enumerator.GetDefaultAudioEndpoint(0, 1) # Render, Multimedia
            if device:
                interface = device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
                self._volume = cast(interface, POINTER(IAudioEndpointVolume))
                
                # Регистрируем коллбэк
                self._callback_obj = VolumeEvents(self.on_change_callback)
                self._volume.RegisterControlChangeNotify(self._callback_obj)
        except Exception as e:
            print(json.dumps({"log": f"Audio init failed: {e}"}), flush=True)
            self._volume = None

    def get_info(self):
        if not HAS_PYCAW: return 0, False
        if not self._volume: self.init_audio()
        if self._volume:
            try:
                vol = int(round(self._volume.GetMasterVolumeLevelScalar() * 100))
                mute = self._volume.GetMute() == 1
                return vol, mute
            except:
                self._volume = None
        return 0, False

    def __del__(self):
        if self._volume and self._callback_obj:
            try:
                self._volume.UnregisterControlChangeNotify(self._callback_obj)
            except: pass

async def save_cover_to_file(props, file_path):
    if not props or not props.thumbnail: return False
    for attempt in range(5):
        try:
            stream = await asyncio.wait_for(props.thumbnail.open_read_async(), timeout=5.0)
            if stream.size == 0: 
                await asyncio.sleep(0.5)
                continue
            reader = DataReader(stream.get_input_stream_at(0))
            await asyncio.wait_for(reader.load_async(stream.size), timeout=5.0)
            raw_data = bytes(reader.read_buffer(stream.size))
            
            # Сохраняем во временный файл
            with open(file_path, "wb") as f:
                f.write(raw_data)
            return True
        except Exception as e:
            await asyncio.sleep(0.5)
    return False

async def main_loop():
    loop = asyncio.get_running_loop()
    update_event = asyncio.Event()
    
    def trigger_update(*args):
        # Используем сохраненный loop для потокобезопасного вызова
        loop.call_soon_threadsafe(update_event.set)

    last_info = {"title": "___INIT___", "artist": "", "playing": False, "volume": -1, "mute": None, "progress": -1.0}
    last_print_time = 0
    last_sent_cover_title = ""
    last_session = None
    last_track_duration = -1.0
    is_first_run = True
    last_poll_time = time.time()
    last_manager_refresh = time.time()
    
    print(json.dumps({"log": "Entering main_loop"}), flush=True)
    vol_manager = SystemVolume(trigger_update)
    
    try:
        manager = await SessionManager.request_async()
        
        # Подписываемся на события медиа-сессий
        def on_sessions_changed(sender, args):
            trigger_update()
        
        manager.add_sessions_changed(on_sessions_changed)
        print(json.dumps({"log": "SessionManager events registered"}), flush=True)
    except Exception as e:
        print(json.dumps({"log": f"Manager Request Error: {str(e)}"}), flush=True)
        return

    while True:
        try:
            # 1. Громкость
            cur_vol, cur_mute = vol_manager.get_info()

            # 2. Медиа (Логика выбора активной сессии)
            session = None
            try:
                all_sessions = manager.get_sessions()
                for s in all_sessions:
                    pb = s.get_playback_info()
                    if pb and pb.playback_status == 4: # Playing
                        try:
                            tm = s.get_timeline_properties()
                            if tm and tm.end_time.total_seconds() > 1.0:
                                session = s
                                break
                        except: pass
                
                if not session:
                    session = manager.get_current_session()
            except: pass

            info = {
                "volume": cur_vol,
                "mute": cur_mute,
                "title": "",
                "artist": "",
                "playing": False,
                "duration": 0.0,
                "progress": 0.0,
                "status": "online",
                "last_update": time.time()
            }

            if session:
                try:
                    pb = session.get_playback_info()
                    timeline = session.get_timeline_properties()
                    
                    if pb:
                        info["playing"] = (pb.playback_status == 4)

                    if timeline:
                        info["duration"] = float(timeline.end_time.total_seconds())
                        info["progress"] = float(timeline.position.total_seconds())
                        
                    # Оптимизация: запрашиваем тяжелые свойства медиа (метаданные и обложку)
                    # только при смене сессии, смене трека (изменение длительности) или при первом запуске.
                    current_duration = info["duration"]
                    if (session != last_session or 
                        current_duration != last_track_duration or 
                        not last_info.get("title")):
                        
                        props = await asyncio.wait_for(session.try_get_media_properties_async(), timeout=2.0)
                        if props:
                            info["title"] = props.title or ""
                            info["artist"] = props.artist or props.album_artist or ""
                            
                        last_session = session
                        last_track_duration = current_duration
                    else:
                        info["title"] = last_info.get("title", "")
                        info["artist"] = last_info.get("artist", "")
                except: pass

            # ЛОГИКА ОТПРАВКИ
            is_media_changed = (info["title"] != last_info.get("title") or 
                               info["artist"] != last_info.get("artist") or 
                               info["playing"] != last_info.get("playing"))
            
            is_vol_changed = (info["volume"] != last_info.get("volume") or 
                             info["mute"] != last_info.get("mute"))
            
            # Прогресс отправляем раз в 10 секунд или при больших скачках (клиент интерполирует прогресс сам)
            is_time_tick = info["playing"] and abs(info["progress"] - last_info.get("progress", 0)) >= 10.0
            is_heartbeat = (time.time() - last_print_time) > 30.0

            if is_media_changed or is_vol_changed or is_time_tick or is_first_run or is_heartbeat:
                last_print_time = time.time()
                
                if (is_media_changed or is_first_run) and session and info["title"] != last_sent_cover_title:
                    async def fetch_cover_task(s, t):
                        nonlocal last_sent_cover_title
                        try:
                            await asyncio.sleep(0.3)
                            props = await asyncio.wait_for(s.try_get_media_properties_async(), timeout=5.0)
                            cover_file = os.path.join(os.path.dirname(__file__), "cover.jpg")
                            if await save_cover_to_file(props, cover_file):
                                print(json.dumps({"cover_event": "updated", "title": t}, ensure_ascii=False), flush=True)
                                last_sent_cover_title = t
                        except Exception as e:
                             print(json.dumps({"log": f"Cover fetch error: {str(e)}"}), flush=True)
                    asyncio.create_task(fetch_cover_task(session, info["title"]))
                
                print(json.dumps(info, ensure_ascii=False), flush=True)
                last_info = info.copy()
                is_first_run = False
            
            # Ждем события или таймаута (для обновления прогресса)
            timeout = 1.0 if info["playing"] else 30.0
            try:
                await asyncio.wait_for(update_event.wait(), timeout=timeout)
                update_event.clear()
            except asyncio.TimeoutError:
                pass
        except Exception as e:
            await asyncio.sleep(1)

def run_scanner():
    try:
        asyncio.run(main_loop())
    except Exception as e:
        print(json.dumps({"log": f"Asyncio run failed: {e}"}), flush=True)

if __name__ == "__main__":
    run_scanner()
