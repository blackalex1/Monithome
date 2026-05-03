import asyncio
import psutil
import os
import ctypes
from pathlib import Path
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
        self.env_path = Path(__file__).parent / ".env"

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
        if action == "get_disks_for_settings":
            all_disks = await asyncio.to_thread(self._get_all_physical_disks)
            env = self._read_env()
            monitored = env.get("MONITORED_DISKS", "").split(",")
            show_new = env.get("SHOW_NEW_DISKS", "true").lower() == "true"
            
            await self.emit_event("disks_for_settings", {
                "all_disks": all_disks,
                "monitored_disks": [m for m in monitored if m],
                "show_new_disks": show_new
            })
        elif action == "update_settings":
            monitored = ",".join(data.get("monitored_disks", []))
            show_new = str(data.get("show_new_disks", True)).lower()
            
            self._write_env("MONITORED_DISKS", monitored)
            self._write_env("SHOW_NEW_DISKS", show_new)
            
            await self._update_disks_state()
            from core.event_bus import event_bus
            await event_bus.emit("ui_config_changed", {"plugin_id": self.plugin_id})

    async def _stats_loop(self):
        try:
            while True:
                await self._update_disks_state()
                await asyncio.sleep(10.0)
        except asyncio.CancelledError:
            pass

    async def _update_disks_state(self):
        # Выполняем тяжелый I/O в отдельном потоке, чтобы не блокировать asyncio
        new_disks = await asyncio.to_thread(self._get_disks)
        
        if not new_disks and self._state.get("disks"):
            self.log("Warning: Disks temporarily missing, keeping previous state", level=30) # WARNING
            return

        self._state["disks"] = new_disks
        await self.emit_state(self._state)

    def _get_all_physical_disks(self):
        """Возвращает список всех подключенных дисков для настроек"""
        disks = []
        for part in psutil.disk_partitions(all=False):
            if 'cdrom' in part.opts or part.fstype == '': continue
            device = part.device.replace('\\', '')
            if not device and part.mountpoint: device = part.mountpoint.replace('\\', '')
            
            label = ""
            if os.name == 'nt':
                try:
                    buf = ctypes.create_unicode_buffer(1024)
                    ctypes.windll.kernel32.GetVolumeInformationW(ctypes.c_wchar_p(part.mountpoint), buf, 1024, None, None, None, None, 0)
                    label = buf.value
                except: pass
            
            disks.append({"device": device, "label": label})
        return disks

    def _get_disks(self):
        env = self._read_env()
        selected_raw = env.get("MONITORED_DISKS", "")
        selected = [s.strip() for s in selected_raw.split(",") if s.strip()]
        show_new = env.get("SHOW_NEW_DISKS", "true").lower() == "true"
        
        disks = []
        for part in psutil.disk_partitions(all=False):
            if 'cdrom' in part.opts or part.fstype == '': continue
            
            try:
                usage = psutil.disk_usage(part.mountpoint)
                device = part.device.replace('\\', '')
                if not device and part.mountpoint: device = part.mountpoint.replace('\\', '')

                is_removable = False
                if os.name == 'nt':
                    d_type = ctypes.windll.kernel32.GetDriveTypeW(ctypes.c_wchar_p(part.mountpoint))
                    if d_type in [2, 4]: is_removable = True

                # Логика фильтрации
                is_selected = device in selected
                if not is_selected:
                    if not show_new: continue
                    if not is_removable: continue

                label = ""
                if os.name == 'nt':
                    try:
                        buf = ctypes.create_unicode_buffer(1024)
                        ctypes.windll.kernel32.GetVolumeInformationW(ctypes.c_wchar_p(part.mountpoint), buf, 1024, None, None, None, None, 0)
                        label = buf.value
                    except: pass

                free_gb = round(usage.free / (1024**3), 1)
                total_gb = round(usage.total / (1024**3), 1)

                disks.append({
                    "device": device,
                    "label": label or (self.i18n("removable_disk", "Съемный диск") if is_removable else self.i18n("local_disk", "Локальный диск")),
                    "total": total_gb,
                    "used": round(usage.used / (1024**3), 1),
                    "free": free_gb,
                    "free_text": self.i18n("free_of", "{free} ГБ из {total} ГБ").format(free=free_gb, total=total_gb),
                    "percent": usage.percent,
                    "is_removable": is_removable
                })
            except: pass
        return disks

    def _read_env(self):
        res = {}
        if not self.env_path.exists(): return res
        try:
            with open(self.env_path, "r", encoding="utf-8") as f:
                for line in f:
                    if "=" in line:
                        k, v = line.strip().split("=", 1)
                        res[k] = v
        except: pass
        return res

    def _write_env(self, key, value):
        env = self._read_env()
        env[key] = value
        try:
            with open(self.env_path, "w", encoding="utf-8") as f:
                for k, v in env.items():
                    f.write(f"{k}={v}\n")
        except: pass
