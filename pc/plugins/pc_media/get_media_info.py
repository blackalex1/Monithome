
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
