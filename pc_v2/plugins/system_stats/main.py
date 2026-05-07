import asyncio
import psutil
import platform
import socket
import os
import subprocess
import json
import ctypes
import wmi
import time
from typing import Dict, Any

import sys
from plugin_engine.base_plugin import BasePlugin
from .afterburner_reader import get_afterburner_stats
from core.event_bus import event_bus

class Plugin(BasePlugin):
    """
    Плагин сбора аппаратной статистики (system_stats v2).
    Использует Helper-процесс для получения данных под админом.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self._loop_task = None
        self._helper_proc = None 
        self._last_helper_start = 0
        self._state = {
            "cpu": 0, "cpu_temp": 0, "ram_percent": 0, "ram_used": 0, "ram_total": 0,
            "gpu_load": 0, "gpu_temp": 0, "has_gpu": False, "gpu_name": "GPU",
            "hostname": socket.gethostname(), "os": platform.system()
        }
        self._cpu_name = None
        self._gpu_name = None
        self._elevation_pending_until = 0
        self.needs_elevation = False
        self.elevation_active = False

    async def on_start(self):
        self.log("system_stats started.")
        self._loop_task = self.create_task(self._stats_loop())
        await asyncio.to_thread(self._start_helper, elevate=False)

    async def on_stop(self):
        if self._loop_task:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        
        try:
            import subprocess
            subprocess.run(["taskkill", "/F", "/IM", "MonitHomeHelper.exe"], capture_output=True)
        except: pass
            
        self.log("system_stats stopped.")

    async def handle_command(self, action: str, data: any):
        if action == "update_sensor_settings":
            self.save_config({"enabled_sensors": data})
            await self._rebuild_widgets_from_settings(data)
            await self._update_and_emit()
        elif action == "elevate":
            self.log("Manual elevation requested via GUI.")
            await asyncio.to_thread(self._start_helper, elevate=True)
            await self._update_and_emit()

    async def check_admin_requirement(self) -> bool:
        return self.needs_elevation

    async def _stats_loop(self):
        try:
            while True:
                await self._update_and_emit()
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            self.log("Stats loop cancelled.")

    async def _update_and_emit(self):
        cfg = self.get_config()
        sensors = cfg.get("enabled_sensors", {})
        new_state = {}
        
        if sensors.get("ram", True):
            ram = psutil.virtual_memory()
            ram_used_gb = round(ram.used / (1024**3), 2)
            ram_total_gb = round(ram.total / (1024**3), 2)
            new_state.update({
                "ram_percent": ram.percent,
                "display_ram_percent": f"{ram.percent}%",
                "ram_used": ram_used_gb,
                "ram_total": ram_total_gb,
                "display_ram_combined": f"{ram.percent}% ({ram_used_gb}/{ram_total_gb} GB)"
            })

        if sensors.get("cpu", True):
            cpu_l = psutil.cpu_percent()
            new_state.update({"cpu": cpu_l, "display_cpu": f"{int(cpu_l)}%"})

        hw_stats = await asyncio.to_thread(self._fetch_hardware_stats)
        if hw_stats:
            new_state.update(hw_stats)
        else:
            # Если данных от хелпера нет (например, перезапуск), сохраняем старые данные GPU/CPU Temp
            for key in ["gpu_load", "gpu_temp", "cpu_temp", "display_gpu_load", "display_gpu_temp", "display_cpu_temp", "has_gpu", "gpu_name"]:
                if key in self._state:
                    new_state[key] = self._state[key]

        self._state = new_state
        await self.emit_state(self._state)
        try:
            event_bus.emit_sync("plugin_state_changed", {"plugin_id": self.plugin_id, "state": self._state})
        except: pass

    def _fetch_hardware_stats(self) -> Dict[str, Any]:
        hw = {}
        cpu_t, gpu_l, gpu_t = 0, 0, 0
        has_gpu = False

        ab_stats = get_afterburner_stats()
        if ab_stats:
            cpu_t = ab_stats.get('cpu_temp', 0)
            gpu_l = ab_stats.get('gpu_load', 0)
            gpu_t = ab_stats.get('gpu_temp', 0)
            current_gpu = ab_stats.get('gpu_name')
            if current_gpu and (not self._gpu_name or self._gpu_name == "GPU"):
                self._gpu_name = current_gpu
            has_gpu = True if self._gpu_name else (gpu_l > 0 or gpu_t > 0)

        if cpu_t == 0 or gpu_t == 0 or not has_gpu:
            helper_data = self._get_stats_from_helper()
            if helper_data:
                if cpu_t == 0: cpu_t = helper_data.get('cpu_temp', 0)
                if gpu_t == 0: gpu_t = helper_data.get('gpu_temp', 0)
                if gpu_l == 0: gpu_l = helper_data.get('gpu_load', 0)
                if helper_data.get('has_gpu'): has_gpu = True
                current_gpu = helper_data.get('gpu_name')
                if current_gpu and (not self._gpu_name or self._gpu_name == "GPU"):
                    self._gpu_name = current_gpu
                has_gpu = has_gpu or (gpu_l > 0 or gpu_t > 0 or self._gpu_name is not None)

        if cpu_t == 0:
            try:
                import pythoncom
                pythoncom.CoInitialize()
                w = wmi.WMI(namespace="root\\LibreHardwareMonitor")
                for s in w.Sensor(SensorType="Temperature"):
                    if any(x in s.Name for x in ["CPU", "Package", "Core"]):
                        cpu_t = round(s.Value, 0)
                        break
            except: pass

        if not self._cpu_name or not self._gpu_name:
            try:
                import winreg
                if not self._cpu_name:
                    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
                    self._cpu_name = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
                    winreg.CloseKey(key)
                
                if not self._gpu_name or self._gpu_name == "GPU":
                    import wmi
                    w = wmi.WMI()
                    for g in w.Win32_VideoController():
                        name = g.Name
                        if any(x in name.upper() for x in ["NVIDIA", "AMD", "RADEON", "INTEL"]):
                            self._gpu_name = name
                            has_gpu = True
                            break
            except: pass

        hw.update({
            "cpu_temp": cpu_t, "display_cpu_temp": f"{int(cpu_t)}°C" if cpu_t else "N/A",
            "gpu_load": gpu_l, "display_gpu_load": f"{int(gpu_l)}%" if gpu_l else "0%",
            "gpu_temp": gpu_t, "display_gpu_temp": f"{int(gpu_t)}°C" if gpu_t else "N/A",
            "has_gpu": has_gpu,
            "cpu_name": self._cpu_name or "CPU",
            "gpu_name": self._gpu_name or "GPU"
        })
        return hw

    def _get_stats_from_helper(self) -> Dict[str, Any]:
        try:
            import mmap
            # Размер 16384 и имя V9 должны строго совпадать с хелпером
            shm = mmap.mmap(-1, 16384, tagname="Local\\MonitHomeSensors_V9", access=mmap.ACCESS_READ)
            try:
                # Читаем весь буфер целиком через срез, это надежнее
                content = shm[:].decode('utf-8').strip('\x00')
                shm.close()
                
                if not content:
                    return None
                
                stats = json.loads(content)
                # Проверка на "протухание" данных (более 10 секунд)
                if time.time() - stats.get('last_update', 0) > 10:
                    self.elevation_active = False
                    return None
                
                is_admin_now = stats.get('is_admin', False) or (ctypes.windll.shell32.IsUserAnAdmin() != 0)
                
                # Стабилизируем статус elevation, чтобы GUI не "прыгал"
                if is_admin_now:
                    self.elevation_active = True
                    self.needs_elevation = False
                else:
                    # Если помощник работает, но без админа - показываем щит сразу
                    self.elevation_active = False
                    self.needs_elevation = True
                
                return stats
            except:
                shm.close()
                return None
        except:
            # Если хелпер пропал, не меняем статус мгновенно, даем ему 5 секунд на перезапуск
            if time.time() > self._elevation_pending_until + 5:
                if not ctypes.windll.shell32.IsUserAnAdmin():
                    self.needs_elevation = True
                    self.elevation_active = False
            return None

    def _start_helper(self, elevate=False):
        now = time.time()
        if now - self._last_helper_start < 5: return
        self._last_helper_start = now
        self._elevation_pending_until = now + 5
        
        try:
            import subprocess
            subprocess.run(["taskkill", "/F", "/IM", "MonitHomeHelper.exe"], capture_output=True)
            time.sleep(0.5)
        except: pass

        try:
            plugin_dir = os.path.dirname(__file__)
            helper_exe = os.path.abspath(os.path.join(plugin_dir, "bin", "MonitHomeHelper.exe"))
            self.log(f"Checking helper EXE: {helper_exe}")
            
            if os.path.exists(helper_exe):
                if elevate:
                    self.log(f"Starting admin helper: {helper_exe}")
                    ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", helper_exe, f"--parent-pid {os.getpid()}", None, 0)
                    self.log(f"ShellExecuteW (admin) returned: {ret}")
                else:
                    self.log(f"Starting normal helper: {helper_exe}")
                    subprocess.Popen([helper_exe, "--parent-pid", str(os.getpid())], creationflags=subprocess.CREATE_NO_WINDOW)
            else:
                self.log(f"Helper EXE NOT FOUND, falling back to script.", 30)
                helper_path = os.path.abspath(os.path.join(plugin_dir, "sensor_helper.py"))
                python_exe = sys.executable
                args = f'"{helper_path}" --parent-pid {os.getpid()}'
                verb = "runas" if elevate else None
                ret = ctypes.windll.shell32.ShellExecuteW(None, verb, python_exe, args, None, 0)
                self.log(f"ShellExecuteW (script) returned: {ret}")
        except Exception as e:
            self.log(f"Failed to start helper: {e}", 30)

    async def _rebuild_widgets_from_settings(self, sensors: dict):
        widgets = []
        if sensors.get("cpu_load", True):
            widgets.append({"id": "cpu_chart", "type": "chart", "label": "cpu_usage", "data_key": "cpu", "color": "#38bdf8", "icon": "cpu"})
        if sensors.get("cpu_temp", True):
            widgets.append({"id": "cpu_temp_chart", "type": "chart", "label": "cpu_temp", "data_key": "cpu_temp", "color": "#ef4444", "unit": "°C", "icon": "cpu"})
        if sensors.get("gpu_load", True):
            widgets.append({"id": "gpu_load_widget", "type": "stat", "label": "gpu_load", "data_key": "display_gpu_load", "icon": "gpu", "color": "#10b981"})
        if sensors.get("gpu_temp", True):
            widgets.append({"id": "gpu_temp_widget", "type": "stat", "label": "gpu_temp", "data_key": "display_gpu_temp", "icon": "gpu", "color": "#f59e0b"})
        if sensors.get("ram", True):
            widgets.append({"id": "ram_combined_widget", "type": "stat", "label": "ram_label", "data_key": "display_ram_combined", "icon": "ram", "color": "#8b5cf6"})
        
        self.save_config({"widgets": widgets, "enabled_sensors": sensors})
        await asyncio.sleep(0.1)
        await event_bus.emit("ui_config_changed", {"plugin_id": self.plugin_id})
