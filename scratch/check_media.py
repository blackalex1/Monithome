import asyncio
import json
import time
from datetime import datetime, timezone
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager

async def check_current_media():
    manager = await SessionManager.request_async()
    session = manager.get_current_session()
    
    if not session:
        print("No active media session found.")
        return

    try:
        props = await session.try_get_media_properties_async()
        pb = session.get_playback_info()
        timeline = session.get_timeline_properties()
        
        print(f"Title: {props.title}")
        print(f"Artist: {props.artist}")
        print(f"Status: {'Playing' if pb.playback_status == 4 else 'Paused/Other'}")
        
        if timeline:
            d = timeline.end_time.total_seconds()
            p_base = timeline.position.total_seconds()
            lut = timeline.last_updated_time
            now = datetime.now(timezone.utc)
            elapsed = (now - lut).total_seconds()
            
            print(f"Base Position (from OS): {p_base:.2f} s")
            print(f"Last Updated (LUT): {lut}")
            print(f"Current Time (Now): {now}")
            print(f"Elapsed since LUT: {elapsed:.2f} s")
            
            p_final = p_base
            if pb.playback_status == 4:
                p_final += max(0, elapsed)
                print(f"Calculated Final Position: {p_final:.2f} s")
            else:
                print("Status is not Playing, compensation not applied.")
                
            print(f"Duration: {d:.2f} s")
            
            # Format to HH:MM:SS
            def format_time(s):
                h = int(s // 3600)
                m = int((s % 3600) // 60)
                sec = int(s % 60)
                if h > 0: return f"{h}:{m:02d}:{sec:02d}"
                return f"{m:02d}:{sec:02d}"

            print(f"Formatted Final Position: {format_time(p_final)}")
            print(f"Formatted Duration: {format_time(d)}")

    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    asyncio.run(check_current_media())
