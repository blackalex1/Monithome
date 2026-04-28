import psutil
import platform
import socket
import threading
import time
import GPUtil
import os
import subprocess
import wmi
from .afterburner_reader import get_afterburner_stats

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self._stop_event = threading.Event()
        
        # Начальное состояние, СИНХРОНИЗИРОВАННОЕ с config.json
        self._stats_cache = {
            "plugin_id": "system_stats",
            "cpu": 0,          # Соответствует cpu_chart
            "cpu_temp": 0,     # Соответствует cpu_temp_chart
            "ram_percent": 0,  # Соответствует ram_widget
            "ram_used": 0,
            "ram_total": 0,
            "gpu_load": 0,     # Соответствует gpu_chart
            "gpu_temp": 0,     # Соответствует gpu_temp_chart
            "has_gpu": False,  # Условие для показа GPU блока
            "hostname": socket.gethostname(),
            "os": platform.system()
        }
        
        self._thread = threading.Thread(target=self._stats_loop, daemon=True)
        self._thread.start()


    def stop(self):
        """Останавливает фоновый поток плагина"""
        self._stop_event.set()

    def _stats_loop(self):
        # Прогрев CPU
        psutil.cpu_percent(interval=None)
        
        while not self._stop_event.is_set():
            try:
                # Базовые данные
                cpu_v = psutil.cpu_percent(interval=None)
                ram = psutil.virtual_memory()
                
                # Данные из Afterburner (приоритет)
                ab_stats = get_afterburner_stats() or {}
                
                # Попытка получить данные через драйвер LibreHardwareMonitor
                driver_stats = {}
                try:
                    ps_script = os.path.join(os.path.dirname(__file__), "get_stats.ps1")
                    if os.path.exists(ps_script):
                        process = subprocess.run(
                            ["powershell", "-ExecutionPolicy", "Bypass", "-File", ps_script],
                            capture_output=True, text=True, encoding='utf-8', errors='ignore'
                        )

                        if process.returncode == 0:
                            out_str = process.stdout
                            json_start = out_str.find('{')
                            if json_start != -1:
                                import json
                                driver_stats = json.loads(out_str[json_start:])
                except: pass

                # Устанавливаем CPU Load из драйвера, если psutil барахлит
                cpu_v = driver_stats.get('cpu_load', cpu_v)

                # CPU Temp
                cpu_t = ab_stats.get('cpu_temp', 0)
                if cpu_t == 0: cpu_t = driver_stats.get('cpu_temp', 0)
                if cpu_t == 0:
                    try:
                        w = wmi.WMI(namespace="root\\wmi")
                        temperature_infos = w.MSAcpi_ThermalZoneTemperature()
                        if temperature_infos:
                            # Формула Кельвины*10 -> Цельсии: (K - 2732) / 10
                            cpu_t = (temperature_infos[0].CurrentTemperature - 2732) / 10.0
                    except:
                        try:
                            temps = psutil.sensors_temperatures()
                            if 'coretemp' in temps: cpu_t = temps['coretemp'][0].current
                        except: pass

                # GPU
                gpu_l = ab_stats.get('gpu_load', 0)
                if gpu_l == 0: gpu_l = driver_stats.get('gpu_load', 0)
                
                gpu_t = ab_stats.get('gpu_temp', 0)
                if gpu_t == 0: gpu_t = driver_stats.get('gpu_temp', 0)
                
                has_gpu = gpu_l > 0 or gpu_t > 0
                gpu_name = ""
                
                # Fallback для GPU если Afterburner не запущен или не видит его
                if not has_gpu:
                    try:
                        gpus = GPUtil.getGPUs()
                        if gpus:
                            gpu = gpus[0]
                            gpu_l = round(gpu.load * 100, 1)
                            gpu_t = gpu.temperature
                            gpu_name = gpu.name
                            has_gpu = True
                    except: pass
                
                if gpu_l > 0 and not gpu_name:
                    try:
                        gpus = GPUtil.getGPUs()
                        if gpus: gpu_name = gpus[0].name
                    except: pass

                # Названия
                cpu_name = driver_stats.get('cpu_name', self._stats_cache.get('cpu_name', ''))
                if not cpu_name or "Family" in cpu_name:
                    try:
                        # Попытка через WMI для получения красивого названия (Intel Core i7...)
                        w = wmi.WMI()
                        processors = w.Win32_Processor()
                        if processors:
                            cpu_name = processors[0].Name
                    except:
                        if not cpu_name: cpu_name = platform.processor()

                gpu_name = ab_stats.get('gpu_name', driver_stats.get('gpu_name', self._stats_cache.get('gpu_name', '')))

                new_stats = {
                    "plugin_id": "system_stats",
                    "cpu": cpu_v,
                    "cpu_temp": cpu_t,
                    "cpu_name": cpu_name,
                    "cpu_temp_name": cpu_name,
                    "ram_percent": ram.percent,
                    "ram_used": round(ram.used / (1024**3), 1),
                    "ram_total": round(ram.total / (1024**3), 1),
                    "gpu_load": gpu_l,
                    "gpu_temp": gpu_t,
                    "gpu_name": gpu_name,
                    "gpu_load_name": gpu_name,
                    "gpu_temp_name": gpu_name,
                    "has_gpu": has_gpu,
                    "hostname": socket.gethostname(),
                    "os": platform.system()
                }
                
                self._stats_cache = new_stats
                self.socketio.emit('stats', new_stats)
            except Exception as e:
                print(f"[SYSTEM] Loop error: {e}")
            
            time.sleep(2)

    def get_stats(self):
        return self._stats_cache

    def get_wizard_data(self):
        """Возвращает метаданные для универсального мастера настройки"""
        sensors = [
            {"id": "cpu", "label": "Загрузка процессора (CPU %)", "type": "chart"},
            {"id": "cpu_temp", "label": "Температура процессора (°C)", "type": "chart"},
            {"id": "ram_percent", "label": "Использование ОЗУ (%)", "type": "stat"},
            {"id": "ram_used", "label": "Использование ОЗУ (ГБ)", "type": "stat"}
        ]
        
        if self._stats_cache.get("has_gpu"):
            sensors.append({"id": "gpu_load", "label": "Загрузка видеокарты (GPU %)", "type": "chart"})
            sensors.append({"id": "gpu_temp", "label": "Температура видеокарты (°C)", "type": "chart"})
            
        return {
            "title": "Настройка Мониторинга",
            "description": "Выберите датчики, которые будут отображаться на планшете.",
            "items": sensors
        }

    def handle_wizard(self, selections):
        """Универсальный метод для сохранения настроек через мастер"""
        import json
        
        widgets = []
        # CPU
        cpu_g = []
        if 'cpu' in selections:
            cpu_g.append({ "id": "cpu_chart", "type": "chart", "label": "CPU Загрузка", "data_key": "cpu", "color": "#38bdf8" })
        if 'cpu_temp' in selections:
            cpu_g.append({ "id": "cpu_temp_chart", "type": "chart", "label": "CPU Темп.", "data_key": "cpu_temp", "color": "#ef4444", "unit": "°C" })
        if cpu_g: widgets.append({ "id": "cpu_row", "type": "row", "children": cpu_g })

        # GPU
        gpu_g = []
        if 'gpu_load' in selections:
            gpu_g.append({ "id": "gpu_chart", "type": "chart", "label": "GPU Загрузка", "data_key": "gpu_load", "color": "#fbbf24" })
        if 'gpu_temp' in selections:
            gpu_g.append({ "id": "gpu_temp_chart", "type": "chart", "label": "GPU Темп.", "data_key": "gpu_temp", "color": "#f97316", "unit": "°C" })
        if gpu_g: widgets.append({ "id": "gpu_row", "type": "row", "condition": "has_gpu", "children": gpu_g })

        # RAM (Умное объединение)
        if 'ram_percent' in selections and 'ram_used' in selections:
            widgets.append({
                "id": "ram_combined_widget",
                "type": "stat",
                "label": "ОЗУ (Занято / Всего)",
                "data_key": "ram_combined", # Специальный ключ для планшета
                "unit": "GB"
            })
        elif 'ram_percent' in selections:
            widgets.append({
                "id": "ram_percent_widget",
                "type": "stat",
                "label": "ОЗУ (%)",
                "data_key": "ram_percent",
                "unit": "%"
            })
        elif 'ram_used' in selections:
            widgets.append({
                "id": "ram_gb_widget",
                "type": "stat",
                "label": "ОЗУ (ГБ)",
                "data_key": "ram_used",
                "unit": "GB"
            })

        self.config["widgets"] = widgets
        
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(self.config, f, indent=2, ensure_ascii=False)

    def handle_command(self, target, action):
        if action == "get_wizard":
            data = self.get_wizard_data()
            self.socketio.emit('wizard_data', {
                "plugin_id": "system_stats", 
                "wizard": data,
                "plugin_info": {"id": "system_stats", "config": self.config}
            })
