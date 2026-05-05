import asyncio
import logging
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
import os
from contextlib import asynccontextmanager

from network.socket_server import sio, socket_manager
from network.discovery import DiscoveryManager
from plugin_engine.manager import plugin_manager
from core.config import config_manager
from core.logging_setup import setup_logging
import socketio

# Импорт роутеров
from api.plugins import router as plugins_router
from api.config import router as config_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("Main")

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting MonitHome PC...")
    await socket_manager.initialize()
    await plugin_manager.initialize()
    
    discovery = DiscoveryManager(port=5000)
    asyncio.create_task(discovery.start())
    app.state.discovery = discovery
    
    logger.info("Server is up and running.")
    yield
    
    logger.info("Shutting down...")
    if hasattr(app.state, 'discovery'):
        await app.state.discovery.stop()
    await plugin_manager.shutdown()

app = FastAPI(lifespan=lifespan)

# Настройка логирования через Socket.IO
setup_logging(sio)

# Подключаем API роутеры
app.include_router(plugins_router)
app.include_router(config_router)

# Раздача статики
from core.config import BUNDLE_DIR
web_dir = os.path.join(BUNDLE_DIR, "web")
if not os.path.exists(web_dir): os.makedirs(web_dir)
app.mount("/static", StaticFiles(directory=web_dir), name="static")

@app.get("/")
async def root():
    return FileResponse(os.path.join(web_dir, "index.html"))

# Монтируем Socket.IO приложение
socket_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    import uvicorn
    # Предварительная проверка прав до старта сервера
    asyncio.run(plugin_manager.pre_check_elevation())
    
    uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="info")
