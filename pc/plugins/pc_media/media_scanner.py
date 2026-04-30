import asyncio
import json
import base64
import sys
import time
import os
import threading
import ctypes

# КРИТИЧНО: Форсируем MTA (Multi-Threaded Apartment) через comtypes
# Это предотвращает "зависание" Winsdk при получении обложек.
try:
    import comtypes
    try: comtypes.CoUninitialize()
    except: pass
    comtypes.CoInitializeEx(0) # 0 = COINIT_MULTITHREADED
    print(json.dumps({"log": "COM initialized as MTA (Multi-Threaded)"}), flush=True)
except Exception as e:
    # Fallback на ctypes
    try:
        ctypes.windll.ole32.CoInitializeEx(None, 0x0)
        print(json.dumps({"log": "COM initialized as MTA via ctypes"}), flush=True)
    except:
        pass

# Принудительно устанавливаем UTF-8 для вывода в Windows
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

# Громкость через pycaw
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
            # Мы уже инициализировали COM как MTA выше
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

async def get_cover_base64(props):
    if not props or not props.thumbnail: return None
    
    for attempt in range(5):
        try:
            # print(json.dumps({"log": f"get_cover_base64: attempt {attempt+1}..."}), flush=True)
            try:
                # В тестовом скрипте это работало без wait_for. 
                # Возможно, wait_for конфликтует с проксированием WinRT.
                stream = await props.thumbnail.open_read_async()
            except Exception as e:
                print(json.dumps({"log": f"get_cover_base64: open_read_async failed for {props.title}: {repr(e)}"}), flush=True)
                await asyncio.sleep(0.5)
                continue

            if stream.size == 0: 
                print(json.dumps({"log": f"get_cover_base64: stream size is 0 for {props.title}, waiting..."}), flush=True)
                await asyncio.sleep(0.5)
                continue

            reader = DataReader(stream.get_input_stream_at(0))
            await reader.load_async(stream.size)
            raw_data = bytes(reader.read_buffer(stream.size))
            
            return base64.b64encode(raw_data).decode('utf-8')
        except Exception as e:
            print(json.dumps({"log": f"get_cover_base64 error for {props.title} (att {attempt+1}): {repr(e)}"}), flush=True)
            await asyncio.sleep(0.5)
            
    return None

async def main_loop():
    last_info = {"title": "___INIT___", "artist": "", "playing": False, "volume": -1, "mute": None}
    last_print_time = 0
    last_sent_cover_title = ""
    is_first_run = True
    
    print(json.dumps({"log": "Entering main_loop"}), flush=True)
    vol_manager = SystemVolume()
    
    print(json.dumps({"log": "Requesting SessionManager..."}), flush=True)
    try:
        # В некоторых случаях request_async может висеть, если не инициализирован COM в правильном режиме
        # Но в отдельном процессе это обычно работает
        manager = await SessionManager.request_async()
        print(json.dumps({"log": "SessionManager acquired"}), flush=True)
    except Exception as e:
        print(json.dumps({"log": f"Manager Request Error: {str(e)}"}), flush=True)
        return

    while True:
        try:
            # 1. Громкость
            cur_vol, cur_mute = vol_manager.get_info()

            # 2. Медиа (Логика из вашего рабочего скрипта)
            session = None
            try:
                all_sessions = manager.get_sessions()
                # Сначала ищем ту, которая реально играет (status 4)
                for s in all_sessions:
                    pb = s.get_playback_info()
                    if pb and pb.playback_status == 4:
                        # Дополнительная проверка: если позиция НЕ равна концу (борьба с "залипшими" вкладками)
                        try:
                            tm = s.get_timeline_properties()
                            if tm and tm.position.total_seconds() < tm.end_time.total_seconds() - 1:
                                session = s
                                break
                        except:
                            pass
                
                # Если играющих нет или все "залипли", пробуем просто текущую
                if not session:
                    session = manager.get_current_session()
                
                # Если и текущей нет, берем первую попавшуюся из списка
                if not session and all_sessions:
                    session = all_sessions[0]
            except Exception as e:
                # print(json.dumps({"log": f"Session lookup error: {e}"}), flush=True)
                pass

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
                    s_id = session.source_app_user_model_id
                    pb = session.get_playback_info()
                    props = await session.try_get_media_properties_async()
                    timeline = session.get_timeline_properties()
                    
                    info.update({
                        "title": props.title or "",
                        "artist": props.artist or props.album_artist or "",
                        "playing": (pb.playback_status == 4) if pb else False,
                    })

                    if timeline:
                        # В Winsdk объекты TimeSpan имеют свойство duration (в 100-нс тиках)
                        # 1 секунда = 10,000,000 тиков
                        try:
                            d_sec = float(timeline.end_time.duration) / 10000000.0
                            p_sec = float(timeline.position.duration) / 10000000.0
                            
                            # Если прогресс больше длительности (бывает на стримах), ограничиваем
                            if p_sec > d_sec and d_sec > 0:
                                p_sec = d_sec
                                
                            info["duration"] = d_sec
                            info["progress"] = p_sec
                        except AttributeError:
                            info["duration"] = float(timeline.end_time.total_seconds())
                            info["progress"] = float(timeline.position.total_seconds())
                except Exception as e:
                    pass

            is_media_changed = (info["title"] != last_info.get("title") or 
                               info["artist"] != last_info.get("artist") or 
                               info["playing"] != last_info.get("playing"))
            
            is_vol_changed = (info["volume"] != last_info.get("volume") or 
                             info["mute"] != last_info.get("mute"))
            
            is_time_tick = info["playing"] and abs(info["progress"] - last_info.get("progress", 0)) > 5.0
            is_heartbeat = (time.time() - last_print_time) > 10.0

            if is_media_changed or is_vol_changed or is_time_tick or is_first_run or is_heartbeat:
                last_print_time = time.time()
                
                # Отправляем обложку только если сменился трек и мы её еще не слали успешно
                should_fetch_cover = (is_media_changed or is_first_run) and session and info["title"] != last_sent_cover_title
                
                if should_fetch_cover:
                    # Запускаем получение обложки в фоне
                    async def fetch_cover_task(s, t, delay_sec):
                        nonlocal last_sent_cover_title
                        try:
                            if delay_sec:
                                await asyncio.sleep(delay_sec)
                            
                            # Попытка получить обложку (внутри get_cover_base64 уже есть ретраи)
                            try:
                                props = await asyncio.wait_for(s.try_get_media_properties_async(), timeout=10.0)
                                if not props: return

                                cover = await asyncio.wait_for(get_cover_base64(props), timeout=15.0)
                                if cover:
                                    print(json.dumps({"cover": cover, "title": t}, ensure_ascii=False), flush=True)
                                    last_sent_cover_title = t # Запоминаем, что для этого трека обложка ушла
                                else:
                                    pass
                            except asyncio.TimeoutError:
                                pass
                        except Exception as e:
                            pass
                    
                    asyncio.create_task(fetch_cover_task(session, info["title"], 0.5 if is_media_changed else 0.1))
                
                # Отправляем основные статы без обложки
                print(json.dumps(info, ensure_ascii=False), flush=True)
                last_info = info.copy()
                is_first_run = False
            
            await asyncio.sleep(0.5)
        except Exception as e:
            print(json.dumps({"log": f"Scanner loop iteration error: {str(e)}"}), flush=True)
            await asyncio.sleep(1)

if __name__ == "__main__":
    try:
        # Убрали явный CoInitialize(), так как winsdk сам инициализирует COM в нужном режиме (MTA),
        # а вызов CoInitialize() переводит поток в STA, что может мешать асинхронным операциям.
        asyncio.run(main_loop())
    except Exception as e:
        print(json.dumps({"log": f"Asyncio run failed: {e}"}), flush=True)
