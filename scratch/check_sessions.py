import asyncio
import json
from datetime import datetime, timezone
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager

async def check_all_sessions():
    manager = await SessionManager.request_async()
    sessions = manager.get_sessions()
    
    print(f"Found {len(sessions)} sessions.")
    
    for i, session in enumerate(sessions):
        try:
            props = await session.try_get_media_properties_async()
            pb = session.get_playback_info()
            timeline = session.get_timeline_properties()
            
            print(f"\n--- Session {i} [{session.source_app_user_model_id}] ---")
            print(f"Title: {props.title}")
            print(f"Status: {pb.playback_status}")
            
            if timeline:
                p = timeline.position.total_seconds()
                d = timeline.end_time.total_seconds()
                print(f"Position: {int(p//60)}:{int(p%60):02d} / {int(d//60)}:{int(d%60):02d}")
            else:
                print("No timeline properties.")

        except Exception as e:
            print(f"Error session {i}: {e}")

if __name__ == "__main__":
    asyncio.run(check_all_sessions())
