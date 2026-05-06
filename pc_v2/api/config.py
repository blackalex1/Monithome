from fastapi import APIRouter, Depends, Request, HTTPException
from core.config import config_manager
from network.socket_server import sio
from core.autostart import AutostartManager
from .security import verify_token

router = APIRouter(prefix="/api/config")

@router.get("", dependencies=[Depends(verify_token)])
async def get_global_config():
    cfg = config_manager.get()
    return {
        "hostname": cfg.hostname,
        "language": cfg.language,
        "theme_color": cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E",
        "autostart": AutostartManager.is_enabled()
    }

@router.post("", dependencies=[Depends(verify_token)])
async def save_global_config(data: dict):
    cfg = config_manager.get()
    if "hostname" in data: cfg.hostname = data["hostname"]
    if "language" in data: cfg.language = data["language"]
    if "theme_color" in data: cfg.theme_color = data["theme_color"]
    
    if "autostart" in data:
        enabled = data["autostart"]
        if AutostartManager.set_autostart(enabled):
            cfg.autostart = enabled
            
    config_manager.save()
    
    from core.event_bus import event_bus
    await event_bus.emit("ui_config_changed", {})
    await sio.emit("theme_update", {"theme_color": cfg.theme_color}, room='authorized')
    return {"success": True}
