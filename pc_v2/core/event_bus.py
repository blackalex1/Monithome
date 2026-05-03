import asyncio
import logging
from typing import Callable, Dict, List, Any

logger = logging.getLogger("EventBus")

class EventBus:
    """
    Асинхронная шина событий. 
    Плагины и ядро общаются через нее, не зная друг о друге (развязка).
    """
    def __init__(self):
        self._subscribers: Dict[str, List[Callable]] = {}

    def subscribe(self, event_name: str, callback: Callable):
        if event_name not in self._subscribers:
            self._subscribers[event_name] = []
        if callback not in self._subscribers[event_name]:
            self._subscribers[event_name].append(callback)

    def unsubscribe(self, event_name: str, callback: Callable):
        if event_name in self._subscribers and callback in self._subscribers[event_name]:
            self._subscribers[event_name].remove(callback)

    async def emit(self, event_name: str, data: Any = None):
        """Асинхронно вызывает все коллбэки, подписанные на событие"""
        if event_name in self._subscribers:
            callbacks = self._subscribers[event_name]
            for callback in callbacks:
                try:
                    if asyncio.iscoroutinefunction(callback):
                        await callback(data)
                    else:
                        callback(data)
                except Exception as e:
                    logger.error(f"Error in event handler for {event_name}: {e}")

# Global instance
event_bus = EventBus()
