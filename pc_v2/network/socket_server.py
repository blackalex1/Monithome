import asyncio
import logging
import time
import msgpack
import socketio
from typing import Dict, Any

from core.config import config_manager
from core.security import SecurityManager
from core.event_bus import event_bus
from plugin_engine.manager import plugin_manager

logger = logging.getLogger("SocketServer")

# Асинхронный сервер Socket.IO (разрешаем CORS для тестов)
sio = socketio.AsyncServer(async_mode='asgi', cors_allowed_origins='*')

class SocketServerManager:
    """Управляет подпиской на шину событий и рассылкой через Socket.IO"""
    def __init__(self):
        self.sio = sio
        # Кэш состояния для батчинга
        self._state_cache: Dict[str, Any] = {}
        self._dirty = False

    async def initialize(self):
        # Подписываемся на события от плагинов
        event_bus.subscribe("plugin_state_changed", self._on_plugin_state_changed)
        event_bus.subscribe("plugin_custom_event", self._on_plugin_custom_event)
        
        # Запускаем фоновую рассылку (батчинг)
        asyncio.create_task(self._broadcast_loop())

    async def _on_plugin_state_changed(self, payload: Dict[str, Any]):
        p_id = payload["plugin_id"]
        state = payload["state"]
        self._state_cache[p_id] = state
        self._dirty = True

    async def _on_plugin_custom_event(self, payload: Dict[str, Any]):
        p_id = payload["plugin_id"]
        event_name = payload["event"]
        data = payload["data"]
        # Отправляем конкретное событие (например, track_changed)
        await self.sio.emit(f"plugin_event:{p_id}", {"event": event_name, "data": data}, room="authorized")

    async def _broadcast_loop(self):
        """Рассылка MessagePack/JSON данных с частотой 10Hz"""
        while True:
            await asyncio.sleep(0.1) # 100ms
            if self._dirty:
                payload = {
                    "stats": self._state_cache,
                    "_server_time": time.time()
                }
                self._dirty = False
                
                try:
                    # MessagePack для Android (быстро)
                    binary_payload = msgpack.packb(payload, use_bin_type=True)
                    await self.sio.emit('stats', binary_payload, room='authorized')
                    
                    # JSON для веб-клиентов (если они еще не умеют MessagePack)
                    await self.sio.emit('stats_json', payload, room='authorized')
                except Exception as e:
                    logger.error(f"Broadcast error: {e}")

socket_manager = SocketServerManager()

# --- Socket.IO Event Handlers ---

@sio.event
async def connect(sid, environ, auth=None):
    token = auth.get("token") if auth else None
    cfg = config_manager.get()
    
    # Для простоты разрешаем локальные соединения (нужно проверять IP)
    # или токены из доверенных
    is_trusted = token in cfg.trusted_tokens if cfg.trusted_tokens else False
    
    if is_trusted or True: # В демо-режиме пускаем всех, в проде добавить IP чек
        await sio.enter_room(sid, 'authorized')
        
        key = cfg.encryption_key
        if not key:
            key = SecurityManager.generate_key()
            config_manager.config.encryption_key = key
            config_manager.save()
            
        await sio.emit('auth_success', {'token': token, 'encryption_key': key}, room=sid)
        logger.info(f"Client {sid} connected and authorized.")
        return True
    
    return False

@sio.event
async def disconnect(sid):
    logger.info(f"Client {sid} disconnected")

@sio.event
async def plugin_command(sid, data):
    p_id = data.get('plugin_id')
    action = data.get('action')
    cmd_data = data.get('data')
    
    if p_id and action:
        # Маршрутизируем команду в плагин-движок
        await plugin_manager.handle_command(p_id, action, cmd_data)
