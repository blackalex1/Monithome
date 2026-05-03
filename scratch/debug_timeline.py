import asyncio
import ctypes
import json
import sys
from datetime import timedelta, datetime

try:
    import comtypes
    comtypes.CoInitializeEx(0)
except:
    pass

from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager

async def debug_timeline():
    print("--- TOTAL MEDIA SESSIONS DEBUG ---")
    manager = await SessionManager.request_async()
    sessions = manager.get_sessions()
    
    print(f"Total sessions found: {len(sessions)}")
    
    for i, s in enumerate(sessions):
        print(f"\n--- SESSION #{i} ---")
        print(f"ID: {s.source_app_user_model_id}")
        try:
            pb = s.get_playback_info()
            print(f"Playback Status: {pb.playback_status if pb else 'NONE'}")
            
            props = await s.try_get_media_properties_async()
            print(f"Track: {props.title if props else 'NONE'} - {props.artist if props else 'NONE'}")
            
            timeline = s.get_timeline_properties()
            if timeline:
                print(f"Progress: {timeline.position} / {timeline.end_time}")
            else:
                print("Timeline: NONE")
        except Exception as e:
            print(f"Error: {e}")
            
    print("\n--- DEBUG END ---")

if __name__ == "__main__":
    asyncio.run(debug_timeline())
