import asyncio
import time
from datetime import datetime, timezone
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager

async def test_dynamic_detection():
    manager = await SessionManager.request_async()
    
    last_base_p_sec = -1.0
    last_poll_time = time.time()
    
    print("Testing Dynamic Detection (10 samples)...")
    
    for i in range(10):
        session = manager.get_current_session()
        if session:
            pb = session.get_playback_info()
            timeline = session.get_timeline_properties()
            
            if timeline:
                base_p_sec = timeline.position.total_seconds()
                now = datetime.now(timezone.utc)
                now_ts = now.timestamp()
                
                status = pb.playback_status if pb else "None"
                
                if last_base_p_sec != -1.0:
                    system_delta = base_p_sec - last_base_p_sec
                    time_delta = now_ts - last_poll_time
                    is_dynamic = abs(system_delta - time_delta) < 0.2
                    
                    print(f"\nSample {i} (Status: {status}):")
                    print(f"System Delta: {system_delta:.3f}s")
                    print(f"IS DYNAMIC? {is_dynamic}")
                    
                    lut = timeline.last_updated_time
                    elapsed = (now - lut).total_seconds()
                    
                    p_final = base_p_sec
                    if status == 4 and not is_dynamic:
                        p_final += max(0, elapsed)
                    
                    print(f"OS Raw Pos: {base_p_sec:.2f}s")
                    print(f"Final Pos:  {p_final:.2f}s")
                    
                last_base_p_sec = base_p_sec
                last_poll_time = now_ts
        else:
            print("No session found.")
            
        await asyncio.sleep(1.0)

if __name__ == "__main__":
    asyncio.run(test_dynamic_detection())
