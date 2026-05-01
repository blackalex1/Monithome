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
from .afterburner_reader import get_afterburner_stats
from base import BasePlugin

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
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
                "cpu_percent": hal_data["cpu_load"], 
                "display_cpu": f"{hal_data['cpu_load']}%",
                
                "ram_percent": ram_percent,
                "display_ram_percent": f"{ram_percent}%",
                
                "ram_used": ram_used,
                "ram_used_percent": ram_percent, # Чтобы полоска работала в ГБ-виджете
                "display_ram_used": f"{ram_used} GB",
                
                "ram_total": ram_total,
                "ram_combined": ram_percent, 
                "display_ram_combined": f"{ram_used} / {ram_total} GB",
                "secondary_ram_combined": f"{ram_percent}%"
            })
            self.update_state(self._state)

    def stop(self):
        self._stop_event.set()

    def _stats_loop(self):
        while not self._stop_event.is_set():
            try:
                # Теперь берем только "тяжелые" или специфичные данные, 
                # которые Ядро не опрашивает (температуры, GPU)
                ab_stats = get_afterburner_stats() or {}
                
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
                                driver_stats = json.loads(out_str[json_start:])
                except: pass

                cpu_t = ab_stats.get('cpu_temp', 0) or driver_stats.get('cpu_temp', 0)
                if cpu_t == 0:
                    try:
                        w = wmi.WMI(namespace="root\\wmi")
                        temperature_infos = w.MSAcpi_ThermalZoneTemperature()
                        if temperature_infos:
                            cpu_t = (temperature_infos[0].CurrentTemperature - 2732) / 10.0
                    except:
                        try:
                            temps = psutil.sensors_temperatures()
                            if 'coretemp' in temps: cpu_t = temps['coretemp'][0].current
                        except: pass

                gpu_l = ab_stats.get('gpu_load', 0) or driver_stats.get('gpu_load', 0)
                gpu_t = ab_stats.get('gpu_temp', 0) or driver_stats.get('gpu_temp', 0)
                has_gpu = gpu_l > 0 or gpu_t > 0
                gpu_name = ab_stats.get('gpu_name', driver_stats.get('gpu_name', ''))
                
                if not has_gpu:
                    try:
                        gpus = GPUtil.getGPUs()
                        if gpus:
                            gpu = gpus[0]
                            gpu_l, gpu_t, gpu_name = round(gpu.load * 100, 1), gpu.temperature, gpu.name
                            has_gpu = True
                    except: pass
                
                cpu_name = driver_stats.get('cpu_name', platform.processor())
                if "Family" in cpu_name:
                    try:
                        w_inst = wmi.WMI()
                        processors = w_inst.Win32_Processor()
                        if processors: cpu_name = processors[0].Name
                    except: pass

                with self._lock:
                    self._state.update({
                        "cpu_temp": cpu_t,
                        "display_cpu_temp": f"{cpu_t}°C",
                        "gpu_load": gpu_l,
                        "display_gpu_load": f"{gpu_l}%",
                        "gpu_temp": gpu_t,
                        "display_gpu_temp": f"{gpu_t}°C",
                        "display_cpu": f"{self._state.get('cpu', 0)}%",
                        "has_gpu": has_gpu,
                        "cpu_name": cpu_name,
                        "gpu_name": gpu_name
                    })
                    self.update_state(self._state)
            except Exception as e:
                self.log(f"Stats loop error: {e}", level="error")
            
            time.sleep(2)

    def get_stats(self):
        return self._state

    def get_wizard_data(self):
        """Возвращает метаданные для универсального мастера настройки"""
        sensors = [
            {"id": "cpu", "label": "cpu_usage", "type": "chart"},
            {"id": "cpu_temp", "label": "cpu_temp", "type": "chart"},
            {"id": "ram_percent", "label": "ram_percent", "type": "stat"},
            {"id": "ram_used", "label": "ram_label", "type": "stat"}
        ]
        
        if self._state.get("has_gpu"):
            sensors.append({"id": "gpu_load", "label": "gpu_usage", "type": "chart"})
            sensors.append({"id": "gpu_temp", "label": "gpu_temp", "type": "chart"})
            
        return {
            "title": "wizard_title",
            "description": "wizard_desc",
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

        if ram_sel_percent and ram_sel_used:
            widgets.append({
                "id": "ram_combined_widget",
                "type": "stat",
                "label": "ram_label",
                "data_key": "ram_combined",
                "icon": "ram",
                "color_ranges": [
                    {"min": 0, "max": 60, "color": "#38bdf8"},
                    {"min": 60, "max": 85, "color": "#f59e0b"},
                    {"min": 85, "max": 100, "color": "#ef4444"}
                ]
            })
        elif ram_sel_percent:
            widgets.append({
                "id": "ram_percent_widget",
                "type": "stat",
                "label": "ram_percent",
                "data_key": "ram_percent",
                "unit": "%",
                "icon": "Layers"
            })
        elif ram_sel_used:
            widgets.append({
                "id": "ram_gb_widget",
                "type": "stat",
                "label": "ram_label",
                "data_key": "ram_used",
                "unit": "GB",
                "icon": "Layers"
            })

        # Обновляем конфиг в памяти
        self.config["widgets"] = widgets
        
        # Сохраняем на диск
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        try:
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2, ensure_ascii=False)
            self.log("Settings saved successfully")
        except Exception as e:
            self.log(f"Failed to save config: {e}", level="error")
            
        # Уведомляем менеджер об изменении UI
        self.manager.broadcast_ui()

    def get_active_items(self):
        """Возвращает список ID активных датчиков из текущего конфига"""
        active = []
        widgets = self.config.get("widgets", [])
        for w in widgets:
            # Если это комбинированный виджет ОЗУ
            if w.get("data_key") == "ram_combined":
                active.extend(["ram_percent", "ram_used"])
                continue
                
            if w.get("type") == "row":
                for child in w.get("children", []):
                    if child.get("data_key"): active.append(child["data_key"])
            elif w.get("data_key"):
                active.append(w["data_key"])
        return active

    def handle_command(self, target, action, data=None):
        if action == "get_wizard":
            wizard_data = self.get_wizard_data()
            self.manager.emit_to_plugin_ui("system_stats", "wizard_data", wizard_data)
        elif action in ["handle_wizard", "save_wizard", "save_settings", "update_config"]:
            # Пытаемся извлечь список из разных форматов
            selections = []
            if isinstance(data, list):
                selections = data
            elif isinstance(data, dict):
                selections = data.get("selections") or data.get("data") or data.get("items") or []
            
            self.handle_wizard(selections)
