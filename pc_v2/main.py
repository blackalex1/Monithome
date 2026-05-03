import asyncio
import logging
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import RedirectResponse
import os
from contextlib import asynccontextmanager

from network.socket_server import sio, socket_manager, handle_get_ui_config
from network.discovery import DiscoveryManager
from plugin_engine.manager import plugin_manager
from core.config import config_manager
import socketio

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("Main")

import time

class SocketIOHandler(logging.Handler):
    _last_msg = None

    def emit(self, record):
        try:
            # Ручная сборка строки во избежание любых побочных эффектов форматтеров
            t = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(record.created))
            msg = f"{t} [{record.levelname}] {record.name}: {record.getMessage()}"
            
            if msg == SocketIOHandler._last_msg:
                return
            
            SocketIOHandler._last_msg = msg
            
            loop = asyncio.get_running_loop()
            loop.create_task(sio.emit("server_log", {"message": msg}, room="authorized"))
        except:
            pass

# Singleton-инициализация
if not hasattr(logging, "_monithome_handler_initialized"):
    socket_log_handler = SocketIOHandler()
    socket_log_handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
    socket_log_handler.name = "MonitHomeSocketHandler"
    
    root_logger = logging.getLogger()
    # Удаляем вообще ВСЁ, что может дублировать логи в сокет
    root_logger.handlers = [h for h in root_logger.handlers if getattr(h, "name", "") != "MonitHomeSocketHandler" and h.__class__.__name__ != "SocketIOHandler"]
            
    root_logger.addHandler(socket_log_handler)
    logging._monithome_handler_initialized = True

@asynccontextmanager
async def lifespan(app: FastAPI):
    # --- Startup ---
    logger.info("Starting MonitHome PC...")
    
    # 1. Запускаем Socket-менеджер (подписки на шину)
    await socket_manager.initialize()
    
    # 2. Инициализируем и запускаем плагины
    await plugin_manager.initialize()
    
    # 3. Запускаем mDNS анонс (для автопоиска планшетом)
    discovery = DiscoveryManager(port=5000)
    asyncio.create_task(discovery.start())
    app.state.discovery = discovery
    
    logger.info("Server is up and running.")
    yield
    
    # --- Shutdown ---
    logger.info("Shutting down...")
    if hasattr(app.state, 'discovery'):
        await app.state.discovery.stop()
    await plugin_manager.shutdown()

# Создаем приложение FastAPI с lifespan обработчиком
app = FastAPI(lifespan=lifespan)

web_dir = os.path.join(os.path.dirname(__file__), "web")
if not os.path.exists(web_dir):
    os.makedirs(web_dir)

app.mount("/static", StaticFiles(directory=web_dir), name="static")

from fastapi.responses import RedirectResponse, FileResponse

@app.get("/")
async def root():
    return FileResponse(os.path.join(web_dir, "index.html"))

from pydantic import BaseModel
import json

class PluginToggleRequest(BaseModel):
    plugin_id: str
    active: bool

@app.get("/api/plugins")
async def get_plugins():
    plugins_dir = os.path.join(os.path.dirname(__file__), "plugins")
    cfg = config_manager.get()
    active_plugins = cfg.active_plugins
    
    result = []
    if os.path.exists(plugins_dir):
        for p_id in os.listdir(plugins_dir):
            p_path = os.path.join(plugins_dir, p_id)
            if not os.path.isdir(p_path) or p_id.startswith("__"):
                continue
                
            p_cfg_path = os.path.join(p_path, "config.json")
            if os.path.exists(p_cfg_path):
                try:
                    with open(p_cfg_path, "r", encoding="utf-8") as f:
                        p_cfg = json.load(f)
                        
                        # Пропускаем скрытые плагины
                        if p_cfg.get("hidden", False):
                            continue
                            
                        result.append({
                            "id": p_id,
                            "active": p_id in active_plugins,
                            "name": p_cfg.get("name", p_id.replace("_", " ").title()),
                            "description": p_cfg.get("description", ""),
                            "version": p_cfg.get("version", "1.0.0")
                        })
                except Exception as e:
                    print(f"[Main] Error reading config for {p_id}: {e}")
    return {"plugins": result}

@app.post("/api/plugins/toggle")
async def toggle_plugin(req: PluginToggleRequest):
    cfg = config_manager.get()
    active_plugins = set(cfg.active_plugins)
    
    if req.active:
        active_plugins.add(req.plugin_id)
        await plugin_manager.start_plugin(req.plugin_id)
    else:
        active_plugins.discard(req.plugin_id)
        await plugin_manager.stop_plugin(req.plugin_id)
        
    config_manager.config.active_plugins = list(active_plugins)
    config_manager.save()
    
    # Уведомляем клиентов об изменении состава плагинов
    from core.event_bus import event_bus
    await event_bus.emit("ui_config_changed", {"plugin_id": req.plugin_id, "active": req.active})
    
    return {"success": True, "active": req.active}

class PluginConfigRequest(BaseModel):
    config_data: dict

@app.get("/api/plugins/{plugin_id}/config")
async def get_plugin_config(plugin_id: str):
    p_cfg_path = os.path.join(os.path.dirname(__file__), "plugins", plugin_id, "config.json")
    if os.path.exists(p_cfg_path):
        with open(p_cfg_path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}

@app.post("/api/plugins/{plugin_id}/config")
async def save_plugin_config(plugin_id: str, req: PluginConfigRequest):
    p_cfg_path = os.path.join(os.path.dirname(__file__), "plugins", plugin_id, "config.json")
    # Создаем папку, если ее нет, хотя она должна быть
    os.makedirs(os.path.dirname(p_cfg_path), exist_ok=True)
    with open(p_cfg_path, "w", encoding="utf-8") as f:
        json.dump(req.config_data, f, indent=2, ensure_ascii=False)
    
    # Уведомляем клиентов об изменении настроек плагина
    from core.event_bus import event_bus
    await event_bus.emit("ui_config_changed", {"plugin_id": plugin_id})
    
    return {"success": True}

@app.get("/api/config")
async def get_global_config():
    cfg = config_manager.get()
    return {
        "hostname": cfg.hostname,
        "language": cfg.language,
        "theme_color": cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
    }

@app.post("/api/config")
async def save_global_config(data: dict):
    cfg = config_manager.get()
    if "hostname" in data: cfg.hostname = data["hostname"]
    if "language" in data: cfg.language = data["language"]
    if "theme_color" in data: cfg.theme_color = data["theme_color"]
    config_manager.save()
    
    # Мгновенно уведомляем планшет об изменениях
    from core.event_bus import event_bus
    await event_bus.emit("ui_config_changed", {})
    await sio.emit("theme_update", {"theme_color": cfg.theme_color}, room='authorized')
    
    return {"success": True}

# Монтируем Socket.IO приложение в FastAPI
socket_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    import uvicorn
    # Запускаем через ASGI
    uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="info")
