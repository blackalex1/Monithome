from fastapi import APIRouter, Depends, Request, HTTPException
from core.config import config_manager
from network.socket_server import sio

router = APIRouter(prefix="/api/config")

async def verify_token(request: Request):
    token = request.headers.get("X-Token") or request.query_params.get("token")
    cfg = config_manager.get()
    valid_tokens = [config_manager.gui_token] if config_manager.gui_token else []
    if cfg.trusted_tokens:
        valid_tokens.extend(cfg.trusted_tokens)
    if not token or token not in valid_tokens:
        raise HTTPException(status_code=401, detail="Unauthorized")

@router.get("", dependencies=[Depends(verify_token)])
async def get_global_config():
    cfg = config_manager.get()
    return {
        "hostname": cfg.hostname,
        "language": cfg.language,
        "theme_color": cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
    }

@router.post("", dependencies=[Depends(verify_token)])
async def save_global_config(data: dict):
    cfg = config_manager.get()
    if "hostname" in data: cfg.hostname = data["hostname"]
    if "language" in data: cfg.language = data["language"]
    if "theme_color" in data: cfg.theme_color = data["theme_color"]
    config_manager.save()
    
    from core.event_bus import event_bus
    await event_bus.emit("ui_config_changed", {})
    await sio.emit("theme_update", {"theme_color": cfg.theme_color}, room='authorized')
    return {"success": True}
