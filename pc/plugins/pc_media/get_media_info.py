
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
