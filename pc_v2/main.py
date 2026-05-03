import asyncio
import logging
from fastapi import FastAPI
from contextlib import asynccontextmanager

from network.socket_server import sio, socket_manager
from plugin_engine.manager import plugin_manager
import socketio

# Настройка логирования
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("Main")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # --- Startup ---
    logger.info("Starting MonitHome PC v2 (Async)...")
    
    # 1. Запускаем Socket-менеджер (подписки на шину)
    await socket_manager.initialize()
    
    # 2. Инициализируем и запускаем плагины
    await plugin_manager.initialize()
    
    logger.info("Server is up and running.")
    yield
    
    # --- Shutdown ---
    logger.info("Shutting down...")
    await plugin_manager.shutdown()

# Создаем приложение FastAPI с lifespan обработчиком
app = FastAPI(lifespan=lifespan)

# Монтируем Socket.IO приложение в FastAPI
socket_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    import uvicorn
    # Запускаем через ASGI
    uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="info")
