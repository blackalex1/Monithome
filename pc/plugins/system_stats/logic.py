import psutil
import platform
import socket
import threading
import time
import GPUtil
import os
import subprocess
import wmi
import json
import ctypes
import ctypes.wintypes
from typing import Dict, List, Any, Optional
from .afterburner_reader import get_afterburner_stats
from base import BasePlugin

def check_requirements():
    """
    Проверяет, может ли плагин работать без прав администратора.
    Если MSI Afterburner запущен, мы можем работать без админа.
    """
    try:
        # Пробуем открыть Shared Memory MSI Afterburner
        # 0x0004 = FILE_MAP_READ
        handle = ctypes.windll.kernel32.OpenFileMappingW(0x0004, False, "MAHMSharedMemory")
        if handle:
            ctypes.windll.kernel32.CloseHandle(handle)
            return True # Afterburner запущен, админ не нужен
    except:
        pass
    return False # Afterburner не найден, нужен админ для прямого доступа к железу

class Plugin(BasePlugin):
    def __init__(self, socketio: Any, config: Dict[str, Any], manager: Any):
        super().__init__(socketio, config, manager)
        self._stop_event = threading.Event()
        
        # Начальное состояние
        self._state = {
            "cpu": 0,
            "cpu_temp": 0,
            "ram_percent": 0,
            "ram_used": 0,
            "ram_total": 0,
            "gpu_load": 0,
            "gpu_temp": 0,
            "has_gpu": False,
            "hostname": socket.gethostname(),
            "os": platform.system()
        }

    def start(self):
        """Запуск фонового потока и подписка на HAL"""
        self.manager.subscribe("hal_update", self._on_hal_update)
        threading.Thread(target=self._stats_loop, daemon=True).start()

    def _on_hal_update(self, hal_data):
        """Обработка данных от Ядра (HAL) с обогащением метаданных"""
        with self._lock:
            ram_used = hal_data["ram_used_gb"]
            ram_total = hal_data["ram_total_gb"]
            ram_percent = hal_data["ram_percent"]
            
            self._state.update({
                "cpu": hal_data["cpu_load"],
                "display_cpu": f"{hal_data['cpu_load']}%",
                "ram_percent": ram_percent,
                "display_ram_percent": f"{ram_percent}%",
                "ram_used": ram_used,
                "display_ram_used": f"{ram_used} GB",
                "ram_total": ram_total,
                "ram_combined": ram_percent, 
                "display_ram_combined": f"{ram_used} / {ram_total} GB",
                "secondary_ram_combined": f"{int(ram_percent)}%",
                "ram_used_total": ram_percent,
                "display_ram_used_total": f"{ram_used} / {ram_total} GB"
            })
            # Мы НЕ вызываем update_state здесь, так как _stats_loop 
            # отправит общий пакет данных с температурами каждые 2 секунды.

    def _stats_loop(self):
        # Кэшируем имена устройств, чтобы не опрашивать WMI/Registry постоянно
        cpu_name = None
        gpu_name = None
        
        while not self._stop_event.is_set():
            try:
                # 1. Сначала пробуем Afterburner (самый быстрый способ через Shared Memory)
                # Он уже запущен от админа и предоставляет нам "безопасный" доступ к данным.
                ab_stats = get_afterburner_stats()
                
                cpu_t = 0
                gpu_l = 0
                gpu_t = 0
                has_gpu = False
                
                if ab_stats:
                    cpu_t = ab_stats.get('cpu_temp', 0)
                    gpu_l = ab_stats.get('gpu_load', 0)
                    gpu_t = ab_stats.get('gpu_temp', 0)
                    has_gpu = gpu_l > 0 or gpu_t > 0
                    if not gpu_name: gpu_name = ab_stats.get('gpu_name')
                
                # 2. Если Afterburner не запущен ИЛИ он пустой, только тогда пробуем системные вызовы
                # Это предотвращает лишние запросы прав админа и ошибки доступа, когда AB работает.
                if not ab_stats:
                    driver_stats = {}
                    # Проверяем, есть ли у нас права админа, прежде чем пытаться дергать систему
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
                            self.log(f"PowerShell stats collection failed: {e}", level="debug")
                    else:
                        self.log("MSI Afterburner is not running and we have no Admin rights for direct hardware access.", level="debug")

                    if not cpu_t: cpu_t = driver_stats.get('cpu_temp', 0)
                    if not gpu_l: gpu_l = driver_stats.get('gpu_load', 0)
                    if not gpu_t: gpu_t = driver_stats.get('gpu_temp', 0)
                    if not cpu_name: cpu_name = driver_stats.get('cpu_name')
                    if not gpu_name: gpu_name = driver_stats.get('gpu_name')

                # 3. Фолбэк для температур (WMI) - только если все еще нет данных и AB не работает
                if not ab_stats and cpu_t == 0:
                    try:
                        w = wmi.WMI(namespace="root\\wmi")
                        temperature_infos = w.MSAcpi_ThermalZoneTemperature()
                        if temperature_infos:
                            cpu_t = (temperature_infos[0].CurrentTemperature - 2732) / 10.0
                    except Exception as e:
                        self.log(f"WMI temperature fetch failed: {e}", level="debug")

                # 4. Фолбэк для GPU (GPUtil)
                if not has_gpu:
                    try:
                        gpus = GPUtil.getGPUs()
                        if gpus:
                            gpu = gpus[0]
                            gpu_l, gpu_t = round(gpu.load * 100, 1), gpu.temperature
                            if not gpu_name: gpu_name = gpu.name
                            has_gpu = True
                    except Exception as e:
                        self.log(f"GPUtil fetch failed: {e}", level="debug")
                
                # 5. Определение имен (через реестр - самый надежный способ без админа)
                if not cpu_name or not gpu_name or "Family" in str(cpu_name) or "GB203" in str(gpu_name):
                    try:
                        import winreg
                        # CPU Name
                        if not cpu_name or "Family" in str(cpu_name):
                            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
                            cpu_name = winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
                            winreg.CloseKey(key)
                        
                        # GPU Name (если Afterburner дал техническое имя или его нет)
                        if not gpu_name or "GB203" in str(gpu_name):
                            # Пробуем найти через реестр видеоадаптеров
                            for i in range(10): # Проверяем первые 10 видеокарт
                                try:
                                    path = rf"SYSTEM\CurrentControlSet\Control\Class\{{4d36e968-e325-11ce-bfc1-08002be10318}}\{i:04d}"
                                    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path)
                                    name = winreg.QueryValueEx(key, "DriverDesc")[0]
                                    winreg.CloseKey(key)
                                    if "NVIDIA" in name or "AMD" in name or "Radeon" in name or "GeForce" in name:
                                        gpu_name = name
                                        if "NVIDIA" in name or "GeForce" in name: break # NVIDIA обычно основная
                                except:
                                    break
                    except Exception as e:
                        self.log(f"Registry name fetch failed: {e}", level="debug")
                    
                    if not cpu_name: cpu_name = platform.processor()

                with self._lock:
                    self._state.update({
                        "cpu_temp": cpu_t,
                        "display_cpu_temp": f"{int(cpu_t)}°C" if cpu_t else "N/A",
                        "gpu_load": gpu_l,
                        "display_gpu_load": f"{int(gpu_l)}%" if gpu_l else "0%",
                        "gpu_temp": gpu_t,
                        "display_gpu_temp": f"{int(gpu_t)}°C" if gpu_t else "N/A",
                        "has_gpu": has_gpu,
                        "cpu_name": cpu_name or "CPU",
                        "gpu_name": gpu_name or "GPU"
                    })
                    self.update_state(self._state)
            except Exception as e:
                self.log(f"Stats loop error: {e}", level="error")
            
            time.sleep(1)

    def get_stats(self):
        return self._state

    def get_wizard_data(self):
        """Возвращает метаданные для универсального мастера настройки"""
        sensors = [
            {"id": "cpu", "label": self.i18n("cpu_usage"), "type": "chart"},
            {"id": "cpu_temp", "label": self.i18n("cpu_temp"), "type": "chart"},
            {"id": "ram_percent", "label": self.i18n("ram_percent"), "type": "stat"},
            {"id": "ram_used", "label": self.i18n("ram_label"), "type": "stat"}
        ]
        
        if self._state.get("has_gpu"):
            sensors.append({"id": "gpu_load", "label": self.i18n("gpu_usage"), "type": "chart"})
            sensors.append({"id": "gpu_temp", "label": self.i18n("gpu_temp"), "type": "chart"})
            
        return {
            "title": self.i18n("wizard_title"),
            "description": self.i18n("wizard_desc"),
            "items": sensors
        }

    def handle_wizard(self, selections):
        """Универсальный метод для сохранения настроек через мастер"""
        widgets = []
        # CPU
        cpu_g = []
        if 'cpu' in selections:
            cpu_g.append({ 
                "id": "cpu_chart", 
                "type": "chart", 
                "label": "cpu_usage", 
                "data_key": "cpu", 
                "color": "#38bdf8", 
                "icon": "cpu" 
            })
        if 'cpu_temp' in selections:
            cpu_g.append({ 
                "id": "cpu_temp_chart", 
                "type": "chart", 
                "label": "cpu_temp", 
                "data_key": "cpu_temp", 
                "color": "#ef4444", 
                "unit": "°C", 
                "icon": "cpu" 
            })
        if cpu_g: widgets.append({ "id": "cpu_row", "type": "row", "children": cpu_g })

        # GPU
        gpu_g = []
        if 'gpu_load' in selections:
            gpu_g.append({ 
                "id": "gpu_chart", 
                "type": "chart", 
                "label": "gpu_usage", 
                "data_key": "gpu_load", 
                "color": "#fbbf24", 
                "icon": "gpu" 
            })
        if 'gpu_temp' in selections:
            gpu_g.append({ 
                "id": "gpu_temp_chart", 
                "type": "chart", 
                "label": "gpu_temp", 
                "data_key": "gpu_temp", 
                "color": "#f97316", 
                "unit": "°C", 
                "icon": "gpu" 
            })
        if gpu_g: widgets.append({ "id": "gpu_row", "type": "row", "condition": "has_gpu", "children": gpu_g })

        # RAM (Умное объединение)
        ram_sel_percent = 'ram_percent' in selections
        ram_sel_used = 'ram_used' in selections

        # RAM (ОЗУ) - Умное объединение согласно запросу пользователя
        if ram_sel_percent and ram_sel_used:
            widgets.append({
                "id": "ram_combined_widget",
                "type": "stat",
                "label": "ram_label", # "ОЗУ"
                "data_key": "ram_combined",
                "icon": "ram",
                "unit": "%"
            })
        elif ram_sel_percent:
            widgets.append({
                "id": "ram_percent_widget",
                "type": "stat",
                "label": "ram_percent_label", # "ОЗУ (%)"
                "data_key": "ram_percent",
                "unit": "%",
                "icon": "ram"
            })
        elif ram_sel_used:
            widgets.append({
                "id": "ram_gb_widget",
                "type": "stat",
                "label": "ram_combined_label", # "ОЗУ (Занято / Всего)"
                "data_key": "ram_used_total",
                "icon": "ram"
            })

        # Сохраняем обновленные виджеты через базовый метод
        self.save_config({"widgets": widgets})

    def get_active_items(self):
        """Возвращает список ID активных датчиков из текущего конфига"""
        active = []
        widgets = self.config.get("widgets", [])
        for w in widgets:
            w_id = w.get("id")
            # ОЗУ требует особой логики из-за разных режимов отображения
            if w_id == "ram_combined_widget":
                active.extend(["ram_percent", "ram_used"])
                continue
            if w_id == "ram_percent_widget":
                active.append("ram_percent")
                continue
            if w_id == "ram_gb_widget":
                active.append("ram_used")
                continue
                
            if w.get("type") == "row":
                for child in w.get("children", []):
                    if child.get("data_key"): active.append(child["data_key"])
            elif w.get("data_key"):
                active.append(w["data_key"])
        return active

    def handle_command(self, sid, target, action, data=None):
        # Сначала даем базе обработать общие команды (мастер настройки)
        if super().handle_command(sid, target, action, data):
            return
        
        # Если появятся другие команды, обрабатываем здесь
        pass
