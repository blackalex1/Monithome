import asyncio
import psutil
import platform
import socket
import os
import subprocess
import json
import ctypes
import wmi
import GPUtil
from typing import Dict, Any

from plugin_engine.base_plugin import BasePlugin
from .afterburner_reader import get_afterburner_stats
from core.event_bus import event_bus

class Plugin(BasePlugin):
    """
    Плагин сбора аппаратной статистики (system_stats v2).
    Использует to_thread для тяжелых вызовов (WMI, PowerShell, GPUtil).
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)
        self._loop_task = None
        self._state = {
            "cpu": 0, "cpu_temp": 0, "ram_percent": 0, "ram_used": 0, "ram_total": 0,
            "gpu_load": 0, "gpu_temp": 0, "has_gpu": False,
            "hostname": socket.gethostname(), "os": platform.system()
        }
        # Кэш имен оборудования
        self._cpu_name = None
        self._gpu_name = None

    async def on_start(self):
        self.log("system_stats started.")
        self._loop_task = asyncio.create_task(self._stats_loop())

    async def on_stop(self):
        if self._loop_task:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        self.log("system_stats stopped.")

    async def handle_command(self, action: str, data: any):
        if action == "handle_wizard":
            # Вызов логики формирования виджетов (аналог старого handle_wizard)
            self._handle_wizard_selections(data)
            await self._update_and_emit()

    async def _stats_loop(self):
        try:
            while True:
                await self._update_and_emit()
                await asyncio.sleep(1.0) # Опрос раз в секунду
        except asyncio.CancelledError:
            self.log("Stats loop cancelled.")

    async def _update_and_emit(self):
        # Быстрые проверки через psutil
        ram = psutil.virtual_memory()
        cpu_load = psutil.cpu_percent(interval=None)
        
        # Обновляем базовые показатели
        self._state.update({
            "cpu": cpu_load,
            "display_cpu": f"{cpu_load}%",
            "ram_percent": ram.percent,
            "display_ram_percent": f"{ram.percent}%",
            "ram_used": round(ram.used / (1024**3), 2),
            "ram_total": round(ram.total / (1024**3), 2),
        })
        self._state["display_ram_used"] = f"{self._state['ram_used']} GB"
        self._state["display_ram_combined"] = f"{self._state['ram_used']} / {self._state['ram_total']} GB"
        self._state["secondary_ram_combined"] = f"{int(ram.percent)}%"
        self._state["ram_used_total"] = ram.percent
        self._state["display_ram_used_total"] = f"{self._state['ram_used']} / {self._state['ram_total']} GB"

        # Тяжелый сбор температур и нагрузок в отдельном потоке
        hw_stats = await asyncio.to_thread(self._fetch_hardware_stats)
        self._state.update(hw_stats)

        await self.emit_state(self._state)

    def _fetch_hardware_stats(self) -> Dict[str, Any]:
        """Синхронный блокирующий метод сбора аппаратной статы"""
        hw = {}
        ab_stats = get_afterburner_stats()
        
        cpu_t = gpu_l = gpu_t = 0
        has_gpu = False

        if ab_stats:
            cpu_t = ab_stats.get('cpu_temp', 0)
            gpu_l = ab_stats.get('gpu_load', 0)
            gpu_t = ab_stats.get('gpu_temp', 0)
            has_gpu = gpu_l > 0 or gpu_t > 0
            if not self._gpu_name: self._gpu_name = ab_stats.get('gpu_name')

        if not ab_stats:
            driver_stats = {}
            is_admin = ctypes.windll.shell32.IsUserAnAdmin() != 0
            if is_admin:
                try:
                    ps_script = os.path.join(os.path.dirname(__file__), "get_stats.ps1")
                    if os.path.exists(ps_script):
                        process = subprocess.run(
                            ["powershell", "-ExecutionPolicy", "Bypass", "-File", ps_script],
                            capture_output=True, text=True, encoding='utf-8', errors='ignore'
                        )
                        if process.returncode == 0:
                            json_start = process.stdout.find('{')
                            if json_start != -1:
                                driver_stats = json.loads(process.stdout[json_start:])
                except Exception as e:
                    self.log(f"PowerShell stats failed: {e}", 10) # DEBUG

            cpu_t = driver_stats.get('cpu_temp', cpu_t)
            gpu_l = driver_stats.get('gpu_load', gpu_l)
            gpu_t = driver_stats.get('gpu_temp', gpu_t)
            if not self._cpu_name: self._cpu_name = driver_stats.get('cpu_name')
            if not self._gpu_name: self._gpu_name = driver_stats.get('gpu_name')

        # WMI Fallback
        if not ab_stats and cpu_t == 0:
            try:
                w = wmi.WMI(namespace="root\\wmi")
                t_infos = w.MSAcpi_ThermalZoneTemperature()
                if t_infos:
                    cpu_t = (t_infos[0].CurrentTemperature - 2732) / 10.0
            except: pass

        # GPUtil Fallback
        if not has_gpu:
            try:
                gpus = GPUtil.getGPUs()
                if gpus:
                    gpu_l, gpu_t = round(gpus[0].load * 100, 1), gpus[0].temperature
                    if not self._gpu_name: self._gpu_name = gpus[0].name
                    has_gpu = True
            except: pass

        # Registry Name Fallbacks
        if not self._cpu_name or not self._gpu_name or "Family" in str(self._cpu_name) or "GB203" in str(self._gpu_name):
            try:
                import winreg
                if not self._cpu_name or "Family" in str(self._cpu_name):
                    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
                    self._cpu_name = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
                    winreg.CloseKey(key)
                if not self._gpu_name or "GB203" in str(self._gpu_name):
                    for i in range(10):
                        try:
                            path = rf"SYSTEM\CurrentControlSet\Control\Class\{{4d36e968-e325-11ce-bfc1-08002be10318}}\{i:04d}"
                            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path)
                            name = winreg.QueryValueEx(key, "DriverDesc")[0]
                            winreg.CloseKey(key)
                            if any(x in name for x in ["NVIDIA", "AMD", "Radeon", "GeForce"]):
                                self._gpu_name = name
                                if "NVIDIA" in name or "GeForce" in name: break
                        except: break
            except: pass

        if not self._cpu_name: self._cpu_name = platform.processor()

        hw.update({
            "cpu_temp": cpu_t, "display_cpu_temp": f"{int(cpu_t)}°C" if cpu_t else "N/A",
            "gpu_load": gpu_l, "display_gpu_load": f"{int(gpu_l)}%" if gpu_l else "0%",
            "gpu_temp": gpu_t, "display_gpu_temp": f"{int(gpu_t)}°C" if gpu_t else "N/A",
            "has_gpu": has_gpu,
            "cpu_name": self._cpu_name or "CPU",
            "gpu_name": self._gpu_name or "GPU"
        })
        return hw

    def _handle_wizard_selections(self, selections: list):
        widgets = []
        cpu_g = []
        if 'cpu' in selections: cpu_g.append({"id": "cpu_chart", "type": "chart", "label": "cpu_usage", "data_key": "cpu", "color": "#38bdf8", "icon": "cpu"})
        if 'cpu_temp' in selections: cpu_g.append({"id": "cpu_temp_chart", "type": "chart", "label": "cpu_temp", "data_key": "cpu_temp", "color": "#ef4444", "unit": "°C", "icon": "cpu"})
        if cpu_g: widgets.append({"id": "cpu_row", "type": "row", "children": cpu_g})

        gpu_g = []
        if 'gpu_load' in selections: gpu_g.append({"id": "gpu_chart", "type": "chart", "label": "gpu_usage", "data_key": "gpu_load", "color": "#fbbf24", "icon": "gpu"})
        if 'gpu_temp' in selections: gpu_g.append({"id": "gpu_temp_chart", "type": "chart", "label": "gpu_temp", "data_key": "gpu_temp", "color": "#f97316", "unit": "°C", "icon": "gpu"})
        if gpu_g: widgets.append({"id": "gpu_row", "type": "row", "condition": "has_gpu", "children": gpu_g})

        ram_sel_percent = 'ram_percent' in selections
        ram_sel_used = 'ram_used' in selections

        if ram_sel_percent and ram_sel_used:
            widgets.append({"id": "ram_combined_widget", "type": "stat", "label": "ram_label", "data_key": "ram_combined", "icon": "ram", "unit": "%"})
        elif ram_sel_percent:
            widgets.append({"id": "ram_percent_widget", "type": "stat", "label": "ram_percent_label", "data_key": "ram_percent", "unit": "%", "icon": "ram"})
        elif ram_sel_used:
            widgets.append({"id": "ram_gb_widget", "type": "stat", "label": "ram_combined_label", "data_key": "ram_used_total", "icon": "ram"})

        self.save_config({"widgets": widgets})
