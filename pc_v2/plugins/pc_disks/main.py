import asyncio
import psutil
import os
import ctypes
from plugin_engine.base_plugin import BasePlugin

class Plugin(BasePlugin):
    """
    Плагин мониторинга дисков (pc_disks v2).
    Асинхронный опрос дисков с использованием to_thread.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self._loop_task: asyncio.Task | None = None
        self._state = {"disks": []}

    async def on_start(self):
        self.log("Starting pc_disks monitoring...")
        # Первый опрос запускаем сразу
        await self._update_disks_state()
        self._loop_task = asyncio.create_task(self._stats_loop())

    async def on_stop(self):
        if self._loop_task:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        self.log("pc_disks monitoring stopped.")

    async def handle_command(self, action: str, data: any):
        self.log(f"Received command: {action}")
        if action == "update_disks":
            await self._update_disks_state()
        elif action == "handle_wizard":
            # Сохраняем новые выбранные диски (data - список id дисков)
            self.save_config({"selected_disks": data})
            await self._update_disks_state()

    async def _stats_loop(self):
        try:
            while True:
                await asyncio.sleep(30.0) # Опрос раз в 30 секунд
                await self._update_disks_state()
        except asyncio.CancelledError:
            self.log("Stats loop cancelled.")

    async def _update_disks_state(self):
        # Выполняем тяжелый I/O в отдельном потоке, чтобы не блокировать asyncio
        new_disks = await asyncio.to_thread(self._get_disks)
        
        if not new_disks and self._state.get("disks"):
            self.log("Warning: Disks temporarily missing, keeping previous state", level=30) # WARNING
            return

        self._state["disks"] = new_disks
        await self.emit_state(self._state)

    def _get_disks(self):
        """Блокирующий метод, запускается в to_thread"""
        disks = []
        try:
            config = self.get_config()
            selected = config.get("selected_disks", [])
            
            for part in psutil.disk_partitions(all=False):
                if 'cdrom' in part.opts or part.fstype == '':
                    continue
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    device = part.device.replace('\\', '')
                    if not device and part.mountpoint:
                        device = part.mountpoint.replace('\\', '')

                    label = ""
                    if os.name == 'nt':
                        try:
                            volumeNameBuffer = ctypes.create_unicode_buffer(1024)
                            ctypes.windll.kernel32.GetVolumeInformationW(
                                ctypes.c_wchar_p(part.mountpoint),
                                volumeNameBuffer, ctypes.sizeof(volumeNameBuffer),
                                None, None, None, None, 0
                            )
                            label = volumeNameBuffer.value
                        except: pass

                    if selected and device not in selected: 
                        continue

                    free_gb = round(usage.free / (1024**3), 1)
                    total_gb = round(usage.total / (1024**3), 1)

                    disks.append({
                        "device": device,
                        "label": label or self.i18n("local_disk", "Локальный диск"),
                        "total": total_gb,
                        "used": round(usage.used / (1024**3), 1),
                        "free": free_gb,
                        "free_text": self.i18n("free_of", "{free} ГБ из {total} ГБ").format(free=free_gb, total=total_gb),
                        "percent": usage.percent
                    })
                except Exception as e: 
                    pass
        except Exception as e: 
            self.log(f"Error getting disks: {e}", level=40)
        return disks
