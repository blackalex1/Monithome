import asyncio
import psutil
from plugin_engine.base_plugin import BasePlugin

class Plugin(BasePlugin):
    """
    Пример унифицированного плагина v2.
    Заменяет старый жестко зашитый `_hal_loop`.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self._loop_task: asyncio.Task | None = None

    async def on_start(self):
        self.log("SysInfo started. Launching monitoring task...")
        self._loop_task = asyncio.create_task(self._monitoring_loop())

    async def on_stop(self):
        if self._loop_task:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        self.log("SysInfo stopped.")

    async def handle_command(self, action: str, data: any):
        """Здесь мы обрабатываем команды от UI"""
        self.log(f"Received command: {action} with data: {data}")
        if action == "force_update":
            await self._update_stats()

    async def _monitoring_loop(self):
        """Асинхронный цикл сбора статистики (не блокирует другие плагины!)"""
        try:
            while True:
                await self._update_stats()
                await asyncio.sleep(2.0) # Отдыхаем 2 секунды
        except asyncio.CancelledError:
            self.log("Monitoring loop cancelled.")

    async def _update_stats(self):
        cpu = psutil.cpu_percent(interval=None)
        ram = psutil.virtual_memory()
        
        state = {
            "cpu_load": cpu,
            "ram_percent": ram.percent,
            "ram_used_gb": round(ram.used / (1024**3), 2),
            "ram_total_gb": round(ram.total / (1024**3), 2)
        }
        
        # Стандартный метод BasePlugin для рассылки состояния клиентам
        await self.emit_state(state)
