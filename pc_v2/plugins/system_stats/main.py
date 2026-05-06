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
        self._helper_proc = None # Для совместимости, но не для поллинга
        self._last_helper_start = 0
        self._state = {
            "cpu": 0, "cpu_temp": 0, "ram_percent": 0, "ram_used": 0, "ram_total": 0,
            "gpu_load": 0, "gpu_temp": 0, "has_gpu": False,
            "hostname": socket.gethostname(), "os": platform.system()
        }
        # Кэш имен оборудования
        self._cpu_name = None
        self._gpu_name = None
        self._elevation_pending_until = 0

    async def on_start(self):
        self.log("system_stats started.")
        self._loop_task = self.create_task(self._stats_loop())

    async def on_stop(self):
        if self._loop_task:
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        
        # Убиваем хелпер при остановке
        if self._helper_proc:
            self._helper_proc.terminate()
            self._helper_proc = None
            
        self.log("system_stats stopped.")

    async def handle_command(self, action: str, data: any):
        if action == "update_sensor_settings":
            # Сохраняем новые настройки сенсоров
            self.save_config({"enabled_sensors": data})
            # Пересобираем виджеты на основе выбранных сенсоров
            await self._rebuild_widgets_from_settings(data)
            await self._update_and_emit()
        elif action == "elevate":
            self.log("Manual elevation requested via GUI.")
            self._start_helper()
            await self._update_and_emit()

    async def check_admin_requirement(self) -> bool:
        """
        Теперь нам НЕ нужны права админа для основного процесса.
        Админ нужен только хелперу, которого мы запустим отдельно.
        """
        return False

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
        self.log(f"Emitting state with {len(self._state)} keys", 10) # DEBUG
        await self.emit_state(self._state)

    def _fetch_hardware_stats(self) -> Dict[str, Any]:
        """Синхронный метод сбора аппаратной статы"""
        hw = {}
        try:
            ab_stats = get_afterburner_stats()
        except:
            ab_stats = None
        
        cpu_t = gpu_l = gpu_t = 0
        has_gpu = False

        # 1. Afterburner (приоритет - быстро и без админа)
        if ab_stats:
            cpu_t = ab_stats.get('cpu_temp', 0)
            gpu_l = ab_stats.get('gpu_load', 0)
            gpu_t = ab_stats.get('gpu_temp', 0)
            has_gpu = gpu_l > 0 or gpu_t > 0
            if not self._gpu_name: self._gpu_name = ab_stats.get('gpu_name')
            if cpu_t > 0:
                self.log(f"Stats from Afterburner: CPU {cpu_t}°C", 10)

        # 2. Если Afterburner не помог - идем к Хелперу
        if cpu_t == 0 or gpu_t == 0:
            helper_data = self._get_stats_from_helper()
            if helper_data:
                self.log(f"Stats from Helper: {helper_data}", 10)
                if cpu_t == 0: cpu_t = helper_data.get('cpu_temp', 0)
                if gpu_t == 0: gpu_t = helper_data.get('gpu_temp', 0)
                if gpu_l == 0: gpu_l = helper_data.get('gpu_load', 0)
                has_gpu = has_gpu or gpu_l > 0 or gpu_t > 0

        # 3. WMI Fallback (если хелпер не работает, но LHM запущен отдельно)
        if cpu_t == 0:
            try:
                import pythoncom
                pythoncom.CoInitialize()
                w = wmi.WMI(namespace="root\\LibreHardwareMonitor")
                for s in w.Sensor(SensorType="Temperature"):
                    if "CPU" in s.Name or "Package" in s.Name or "Core" in s.Name:
                        cpu_t = round(s.Value, 0)
                        break
            except: pass

        # 3. Registry Name Fallbacks
        if not self._cpu_name or not self._gpu_name:
            try:
                import winreg
                if not self._cpu_name:
                    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
                    self._cpu_name = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
                    winreg.CloseKey(key)
                if not self._gpu_name:
                    # Попытка найти имя GPU в реестре
                    self._gpu_name = "GPU"
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
        """Чтение данных из Shared Memory хелпера"""
        try:
            import mmap
            # Пробуем открыть память
            shm = mmap.mmap(-1, 1024, tagname="Local\\MonitHomeSensors", access=mmap.ACCESS_READ)
            try:
                data_raw = shm.read(1024).decode('utf-8').strip('\x00')
                shm.close()
                
                if not data_raw:
                    # Если данных нет и мы не админ - просим прав
                    if not ctypes.windll.shell32.IsUserAnAdmin():
                        if time.time() > self._elevation_pending_until:
                            self.needs_elevation = True
                        self.elevation_active = False
                    return None
                
                stats = json.loads(data_raw)
                # Проверяем "свежесть" данных (не старше 5 секунд)
                if time.time() - stats.get('last_update', 0) > 5:
                    if not ctypes.windll.shell32.IsUserAnAdmin():
                        if time.time() > self._elevation_pending_until:
                            self.needs_elevation = True
                        self.elevation_active = False
                    return None
                
                self.needs_elevation = False # Всё ок, данные идут
                self.elevation_active = True
                return stats
            except Exception as e:
                if 'shm' in locals(): shm.close()
                if not ctypes.windll.shell32.IsUserAnAdmin():
                    self.needs_elevation = True
                    self.elevation_active = False
                return None
        except:
            # Памяти нет вообще - значит хелпер не запущен
            if not ctypes.windll.shell32.IsUserAnAdmin():
                if time.time() > self._elevation_pending_until:
                    self.needs_elevation = True
                self.elevation_active = False
            return None

    def _start_helper(self):
        """Запуск хелпера с кулдауном 10 секунд. Приоритет - скомпилированный EXE."""
        now = time.time()
        if now - self._last_helper_start < 10:
            return
        
        self._last_helper_start = now
        self._elevation_pending_until = now + 10 # Даем 10 секунд на запуск
        self.needs_elevation = False # Сбрасываем флаг, т.к. процесс запуска инициирован
        self.elevation_active = False
        
        try:
            plugin_dir = os.path.dirname(__file__)
            # 1. Сначала ищем скомпилированный EXE (чтобы запрос админа был красивым)
            helper_exe = os.path.abspath(os.path.join(plugin_dir, "bin", "MonitHomeHelper.exe"))
            
            if os.path.exists(helper_exe):
                self.log(f"Starting compiled helper: {helper_exe}")
                # Запускаем EXE. Он сам попросит админа, т.к. собран с --uac-admin
                import ctypes
                ctypes.windll.shell32.ShellExecuteW(None, "open", helper_exe, f"--parent-pid {os.getpid()}", None, 0)
            else:
                # 2. Если EXE нет (режим разработки), запускаем скрипт через Python
                self.log("Helper EXE not found, falling back to script.")
                helper_path = os.path.abspath(os.path.join(plugin_dir, "sensor_helper.py"))
                python_exe = sys.executable
                args = f'"{helper_path}" --parent-pid {os.getpid()}'
                import ctypes
                ctypes.windll.shell32.ShellExecuteW(None, "runas", python_exe, args, None, 0)
                
            self.log(f"Sensor helper start triggered with parent PID {os.getpid()}")
        except Exception as e:
            self.log(f"Failed to trigger helper: {e}", 30)

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
