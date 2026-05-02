import asyncio
import json
import base64
import sys
import time
import os
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
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    from comtypes import CLSCTX_ALL
    from ctypes import cast, POINTER
    HAS_PYCAW = True
except Exception as e:
    print(json.dumps({"log": f"Pycaw import failed: {e}"}), flush=True)
    HAS_PYCAW = False

class SystemVolume:
    def __init__(self):
        self._volume = None
        if HAS_PYCAW:
            self.init_audio()

    def init_audio(self):
        try:
            enumerator = AudioUtilities.GetDeviceEnumerator()
            device = enumerator.GetDefaultAudioEndpoint(0, 1)
            if device:
                interface = device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
                self._volume = cast(interface, POINTER(IAudioEndpointVolume))
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

async def save_cover_to_file(props, file_path):
    if not props or not props.thumbnail: return False
    for attempt in range(5):
        try:
            stream = await props.thumbnail.open_read_async()
            if stream.size == 0: 
                await asyncio.sleep(0.5)
                continue
            reader = DataReader(stream.get_input_stream_at(0))
            await reader.load_async(stream.size)
            raw_data = bytes(reader.read_buffer(stream.size))
            
            # Сохраняем во временный файл
            with open(file_path, "wb") as f:
                f.write(raw_data)
            return True
        except Exception as e:
            await asyncio.sleep(0.5)
    return False

async def main_loop():
    last_info = {"title": "___INIT___", "artist": "", "playing": False, "volume": -1, "mute": None, "progress": -1.0}
    last_print_time = 0
    last_sent_cover_title = ""
    is_first_run = True
    
    print(json.dumps({"log": "Entering main_loop"}), flush=True)
    vol_manager = SystemVolume()
    
    try:
        manager = await SessionManager.request_async()
        print(json.dumps({"log": "SessionManager acquired"}), flush=True)
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
                # Приоритет 1: Реально играющая сессия
                for s in all_sessions:
                    pb = s.get_playback_info()
                    if pb and pb.playback_status == 4: # Playing
                        try:
                            tm = s.get_timeline_properties()
                            if tm and tm.end_time.total_seconds() > 1.0:
                                session = s
                                break
                        except: pass
                
                # Приоритет 2: Текущая системная сессия
                if not session:
                    session = manager.get_current_session()
                    if session:
                        try:
                            tm = session.get_timeline_properties()
                            if not tm or tm.end_time.total_seconds() <= 1.0:
                                session = None
                        except: session = None
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
                    props = await session.try_get_media_properties_async()
                    timeline = session.get_timeline_properties()
                    
                    info.update({
                        "title": props.title or "",
                        "artist": props.artist or props.album_artist or "",
                        "playing": (pb.playback_status == 4) if pb else False,
                    })

                    # Если трек уже доиграл до конца (статус 5 или позиция >= длительности)
                    if pb and pb.playback_status == 5:
                        session = None
                        info["playing"] = False

                    if timeline:
                        try:
                            d_sec = float(timeline.end_time.duration) / 10000000.0
                            base_p_sec = float(timeline.position.duration) / 10000000.0
                            
                            # КОМПЕНСАЦИЯ ВРЕМЕНИ: Firefox и другие браузеры редко обновляют позицию.
                            # Мы добавляем время, прошедшее с момента последнего обновления сессии.
                            lut = timeline.last_updated_time.timestamp()
                            elapsed = time.time() - lut
                            
                            p_sec = base_p_sec
                            if pb and pb.playback_status == 4: # Если играет - добавляем прошедшее время
                                p_sec += elapsed
                                
                            if p_sec > d_sec and d_sec > 0: p_sec = d_sec
                            
                            # Фильтр: если до конца осталось меньше 0.5 сек - считаем трек законченным
                            if d_sec > 0 and p_sec >= d_sec - 0.5:
                                session = None
                                info["playing"] = False
                                info["title"] = ""
                                info["artist"] = ""
                                
                            info["duration"] = d_sec
                            info["progress"] = p_sec
                        except AttributeError:
                            info["duration"] = float(timeline.end_time.total_seconds())
                            info["progress"] = float(timeline.position.total_seconds())
                except: pass

            # ЛОГИКА ОТПРАВКИ
            is_media_changed = (info["title"] != last_info.get("title") or 
                               info["artist"] != last_info.get("artist") or 
                               info["playing"] != last_info.get("playing"))
            
            if is_media_changed:
                info["progress"] = 0.0
                info["duration"] = 0.0
                
            is_vol_changed = (info["volume"] != last_info.get("volume") or 
                             info["mute"] != last_info.get("mute"))
            
            # Порог тика времени уменьшен до 0.5с для плавности
            is_time_tick = info["playing"] and abs(info["progress"] - last_info.get("progress", 0)) >= 0.5
            is_heartbeat = (time.time() - last_print_time) > 10.0

            if is_media_changed or is_vol_changed or is_time_tick or is_first_run or is_heartbeat:
                last_print_time = time.time()
                
                if (is_media_changed or is_first_run) and session and info["title"] != last_sent_cover_title:
                    async def fetch_cover_task(s, t):
                        nonlocal last_sent_cover_title
                        try:
                            await asyncio.sleep(0.3)
                            props = await asyncio.wait_for(s.try_get_media_properties_async(), timeout=5.0)
                            if not props: return
                            
                            cover_file = os.path.join(os.path.dirname(__file__), "cover.jpg")
                            success = await asyncio.wait_for(save_cover_to_file(props, cover_file), timeout=10.0)
                            
                            if success:
                                # Вместо Base64 отправляем только сигнал о готовности файла
                                print(json.dumps({"cover_event": "updated", "title": t}, ensure_ascii=False), flush=True)
                                last_sent_cover_title = t
                        except Exception as e:
                            print(json.dumps({"log": f"Cover fetch error: {e}"}), flush=True)
                    asyncio.create_task(fetch_cover_task(session, info["title"]))
                
                print(json.dumps(info, ensure_ascii=False), flush=True)
                last_info = info.copy()
                is_first_run = False
            
            await asyncio.sleep(0.5)
        except Exception as e:
            await asyncio.sleep(1)

if __name__ == "__main__":
    try:
        asyncio.run(main_loop())
    except Exception as e:
        print(json.dumps({"log": f"Asyncio run failed: {e}"}), flush=True)
