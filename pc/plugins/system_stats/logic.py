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
        self._cached_disks = []
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
            "disks": [],
            "hostname": socket.gethostname(),
            "os": platform.system()
        }
        
        self._update_disks()
        
        self._thread = threading.Thread(target=self._stats_loop, daemon=True)
        self._thread.start()

    def _update_disks(self):
        disks = []
        try:
            for partition in psutil.disk_partitions():
                if 'cdrom' in partition.opts or partition.fstype == '': continue
                try:
                    usage = psutil.disk_usage(partition.mountpoint)
                    disks.append({
                        "device": str(partition.device),
                        "mountpoint": str(partition.mountpoint),
                        "total": round(usage.total / (1024**3), 1),
                        "used": round(usage.used / (1024**3), 1),
                        "percent": usage.percent
                    })
                except: pass
            self._cached_disks = disks
            self._stats_cache["disks"] = disks
        except: pass

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
                cpu_name = self._stats_cache.get('cpu_name', '')
                if not cpu_name:
                    try:
                        cpu_name = subprocess.check_output("wmic cpu get name", shell=True).decode().split('\n')[1].strip()
                    except: cpu_name = platform.processor()

                new_stats = {
                    "plugin_id": "system_stats",
                    "cpu": cpu_v,
                    "cpu_temp": cpu_t,
                    "cpu_name": cpu_name,
                    "ram_percent": ram.percent,
                    "ram_used": round(ram.used / (1024**3), 1),
                    "ram_total": round(ram.total / (1024**3), 1),
                    "gpu_load": gpu_l,
                    "gpu_temp": gpu_t,
                    "gpu_name": gpu_name,
                    "has_gpu": has_gpu,
                    "disks": self._cached_disks,
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

    def handle_command(self, target, action):
        if action == "refresh_disks":
            self._update_disks()
            self.socketio.emit('stats', self.get_stats())
