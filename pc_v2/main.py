import asyncio
import logging
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import RedirectResponse
import os
from contextlib import asynccontextmanager

from network.socket_server import sio, socket_manager
from network.discovery import DiscoveryManager
from plugin_engine.manager import plugin_manager
from core.config import config_manager
import socketio

# Настройка логирования
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("Main")

class SocketIOHandler(logging.Handler):
    def emit(self, record):
        log_entry = self.format(record)
        try:
            # Отправляем логи только если есть event loop
            loop = asyncio.get_running_loop()
            loop.create_task(sio.emit("server_log", {"message": log_entry}, room="authorized"))
        except RuntimeError:
            pass

socket_log_handler = SocketIOHandler()
socket_log_handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
# Добавляем хендлер к корневому логгеру, чтобы ловить всё
logging.getLogger().addHandler(socket_log_handler)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # --- Startup ---
    logger.info("Starting MonitHome PC v2 (Async)...")
    
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

@app.get("/")
async def root():
    return RedirectResponse(url="/static/index.html")

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
    return {"success": True}

# Монтируем Socket.IO приложение в FastAPI
socket_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    import uvicorn
    # Запускаем через ASGI
    uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="info")
