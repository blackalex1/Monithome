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
        if action == "update_sensor_settings":
            # Сохраняем новые настройки сенсоров
            self.save_config({"enabled_sensors": data})
            # Пересобираем виджеты на основе выбранных сенсоров
            await self._rebuild_widgets_from_settings(data)
            await self._update_and_emit()

    async def check_admin_requirement(self) -> bool:
        """
        Проверяем, нужны ли нам права админа.
        Если Afterburner запущен - мы можем работать без админа.
        Если нет - для работы DLL драйвера (температуры) нужны права.
        """
        # Если мы уже админы - дополнительные права не нужны
        if ctypes.windll.shell32.IsUserAnAdmin() != 0:
            return False

        # Проверяем Afterburner
        ab_stats = get_afterburner_stats()
        if ab_stats and ab_stats.get('cpu_temp', 0) > 0:
            # Afterburner запущен и отдает температуру, админ не нужен
            return False
            
        # Afterburner нет или он пуст -> нужны права для DLL драйвера
        return True

    async def _stats_loop(self):
        try:
            while True:
                await self._update_and_emit()
                await asyncio.sleep(1.0) # Опрос раз в секунду
        except asyncio.CancelledError:
            self.log("Stats loop cancelled.")

    async def _update_and_emit(self):
        cfg = self.get_config()
        sensors = cfg.get("enabled_sensors", {})
        new_state = {}
        
        # 1. Быстрые проверки через psutil
        if sensors.get("ram", True):
            ram = psutil.virtual_memory()
            ram_used_gb = round(ram.used / (1024**3), 2)
            ram_total_gb = round(ram.total / (1024**3), 2)
            new_state.update({
                "ram_percent": ram.percent,
                "display_ram_percent": f"{ram.percent}%",
                "ram_used": ram_used_gb,
                "ram_total": ram_total_gb,
                "display_ram_used": f"{ram_used_gb} GB",
                "display_ram_combined": f"{ram_used_gb} / {ram_total_gb} GB",
                "secondary_ram_combined": f"{int(ram.percent)}%",
                "ram_used_total": ram.percent,
                "display_ram_used_total": f"{ram_used_gb} / {ram_total_gb} GB"
            })

        if sensors.get("cpu_load", True):
            cpu_load = psutil.cpu_percent(interval=None)
            new_state.update({
                "cpu": cpu_load,
                "display_cpu": f"{cpu_load}%"
            })

        # 2. Тяжелый сбор (Температуры, GPU)
        # Собираем только если включено хоть что-то из тяжелого
        if sensors.get("cpu_temp", True) or sensors.get("gpu_load", True) or sensors.get("gpu_temp", True):
            hw_stats = await asyncio.to_thread(self._fetch_hardware_stats)
            # Фильтруем hw_stats перед добавлением
            if not sensors.get("cpu_temp", True):
                hw_stats.pop("cpu_temp", None)
                hw_stats.pop("display_cpu_temp", None)
            if not sensors.get("gpu_load", True):
                hw_stats.pop("gpu_load", None)
                hw_stats.pop("display_gpu_load", None)
            if not sensors.get("gpu_temp", True):
                hw_stats.pop("gpu_temp", None)
                hw_stats.pop("display_gpu_temp", None)
                
            new_state.update(hw_stats)

        self._state = new_state
        await self.emit_state(self._state)

    def _fetch_hardware_stats(self) -> Dict[str, Any]:
        """Синхронный блокирующий метод сбора аппаратной статы"""
        hw = {}
        ab_stats = get_afterburner_stats()
        
        cpu_t = gpu_l = gpu_t = 0
        has_gpu = False

        # 1. Сначала пробуем Afterburner (самый легкий способ)
        if ab_stats:
            cpu_t = ab_stats.get('cpu_temp', 0)
            gpu_l = ab_stats.get('gpu_load', 0)
            gpu_t = ab_stats.get('gpu_temp', 0)
            has_gpu = gpu_l > 0 or gpu_t > 0
            if not self._gpu_name: self._gpu_name = ab_stats.get('gpu_name')

        # 2. Если что-то не нашли (температуры == 0) и мы Админ - пробуем драйвер (DLL)
        if cpu_t == 0 or gpu_t == 0:
            is_admin = ctypes.windll.shell32.IsUserAnAdmin() != 0
            if is_admin:
                driver_stats = {}
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
                    self.log(f"PowerShell stats failed: {e}", 10)

                # Дополняем данные из драйвера, если они там есть
                if cpu_t == 0: cpu_t = driver_stats.get('cpu_temp', 0)
                if gpu_l == 0: gpu_l = driver_stats.get('gpu_load', 0)
                if gpu_t == 0: gpu_t = driver_stats.get('gpu_temp', 0)
                if not self._cpu_name: self._cpu_name = driver_stats.get('cpu_name')
                if not self._gpu_name: self._gpu_name = driver_stats.get('gpu_name')
            else:
                if cpu_t == 0:
                    self.log("CPU temperature is 0 and no Admin rights for DLL driver.", 30)
            
            if cpu_t == 0 and not is_admin:
                self.log("CPU temperature is 0. Please try running the server as Administrator for better sensor access.", 30) # WARNING

        # WMI Fallback (LibreHardwareMonitor or OpenHardwareMonitor namespace)
        if cpu_t == 0:
            try:
                # Пытаемся найти через WMI-пространство LibreHardwareMonitor (если запущен)
                w = wmi.WMI(namespace="root\\LibreHardwareMonitor")
                sensors = w.Sensor()
                for s in sensors:
                    if s.SensorType == "Temperature":
                        if "CPU" in s.Name or "Package" in s.Name:
                            if cpu_t == 0 or s.Value > cpu_t:
                                cpu_t = s.Value
            except: 
                try:
                    # Стандартный WMI ThermalZone (часто требует прав или специфичного BIOS)
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
            "disk_temps": driver_stats.get('disk_temps', []),
            "has_gpu": has_gpu,
            "cpu_name": self._cpu_name or "CPU",
            "gpu_name": self._gpu_name or "GPU"
        })
        return hw

    async def _rebuild_widgets_from_settings(self, sensors: dict):
        widgets = []
        
        # CPU Load
        if sensors.get("cpu_load", True):
            widgets.append({"id": "cpu_chart", "type": "chart", "label": "cpu_usage", "data_key": "cpu", "color": "#38bdf8", "icon": "cpu"})
            
        # CPU Temp
        if sensors.get("cpu_temp", True):
            widgets.append({"id": "cpu_temp_chart", "type": "chart", "label": "cpu_temp", "data_key": "cpu_temp", "color": "#ef4444", "unit": "°C", "icon": "cpu"})

        # GPU Section
        if sensors.get("gpu_load", True):
            widgets.append({"id": "gpu_load_widget", "type": "stat", "label": "gpu_load", "data_key": "display_gpu_load", "icon": "gpu", "color": "#10b981"})
        if sensors.get("gpu_temp", True):
            widgets.append({"id": "gpu_temp_widget", "type": "stat", "label": "gpu_temp", "data_key": "display_gpu_temp", "icon": "gpu", "color": "#f59e0b"})

        # RAM Section
        if sensors.get("ram", True):
            widgets.append({
                "id": "ram_combined_widget", 
                "type": "stat", 
                "label": "ram_label", 
                "data_key": "display_ram_combined", 
                "icon": "ram", 
                "unit": "%"
            })

        self.save_config({
            "widgets": widgets,
            "enabled_sensors": sensors
        })
        
        # Даем системе время на запись файла и уведомляем всех
        await asyncio.sleep(0.1)
        await event_bus.emit("ui_config_changed", {"plugin_id": self.plugin_id})
