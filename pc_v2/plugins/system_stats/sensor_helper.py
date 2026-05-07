import os
import sys
import json
import time
import ctypes
import mmap
import argparse

# Настройка логирования в файл
def setup_logging():
    try:
        # Пытаемся создать лог в папке с хелпером
        if hasattr(sys, '_MEIPASS'):
            log_dir = os.path.dirname(sys.executable)
        else:
            log_dir = os.path.dirname(os.path.abspath(__file__))
        
        log_path = os.path.join(log_dir, "helper_debug.log")
        return open(log_path, "a", encoding="utf-8", buffering=1)
    except:
        return None

log_file = setup_logging()
if log_file:
    sys.stdout = log_file
    sys.stderr = log_file

def log(msg):
    if log_file:
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
        log_file.write(f"[{timestamp}] {msg}\n")
        log_file.flush()

log(f"--- Helper starting (PID: {os.getpid()}) ---")

def init_clr_and_dll(dll_path):
    log(f"Loading DLL: {dll_path}")
    try:
        import clr
        if not os.path.exists(dll_path):
            log(f"CRITICAL: DLL not found at {dll_path}")
            return None
        clr.AddReference(dll_path)
        from LibreHardwareMonitor.Hardware import Computer
        c = Computer()
        c.IsCpuEnabled = True
        c.IsGpuEnabled = True
        c.IsMemoryEnabled = True
        c.IsMotherboardEnabled = True
        c.IsStorageEnabled = True
        c.Open()
        log("LHM Computer initialized successfully")
        return c
    except Exception as e:
        log(f"DLL Init Error: {e}")
        return None

def is_process_running(pid):
    if pid <= 0: return True
    # GetProcessVersion возвращает 0, если процесс не найден
    return ctypes.windll.kernel32.GetProcessVersion(pid) != 0

SHMEM_NAME = "Local\\MonitHomeSensors_V9"
SHMEM_SIZE = 16384
MUTEX_NAME = "Local\\MonitHomeSensorHelperMutex_V5"

class SensorHelper:
    def __init__(self, parent_pid=0):
        self.computer = None
        self.shmem = None
        self.parent_pid = parent_pid
        self.is_admin = ctypes.windll.shell32.IsUserAnAdmin() != 0
        log(f"Helper context: Admin={self.is_admin}, ParentPID={self.parent_pid}")

    def run(self, dll_path):
        self.computer = init_clr_and_dll(dll_path)
        try:
            log(f"Opening shared memory: {SHMEM_NAME}")
            self.shmem = mmap.mmap(-1, SHMEM_SIZE, tagname=SHMEM_NAME)
            log("Shared memory opened")
            
            while True:
                if self.parent_pid > 0 and not is_process_running(self.parent_pid):
                    log("Parent process died, exiting...")
                    break

                stats = {
                    "is_admin": self.is_admin,
                    "last_update": time.time(),
                    "pid": os.getpid(),
                    "sensors": {},
                    "gpu_temp": 0, "gpu_load": 0, "cpu_temp": 0, "has_gpu": False, "gpu_name": None
                }
                
                if self.computer:
                    try:
                        # Сбор данных
                        for hardware in self.computer.Hardware:
                            hardware.Update()
                            h_name = hardware.Name
                            stats["sensors"][h_name] = {}
                            for sensor in hardware.Sensors:
                                if sensor.Value is not None:
                                    s_type = str(sensor.SensorType)
                                    s_name = sensor.Name
                                    # Пропускаем детальные D3D/PCIe данные, которые переполняют буфер
                                    if any(x in s_name for x in ["D3D", "PCIe", "Security", "Optical Flow", "JPEG Decode", "VR", "Copy"]):
                                        continue
                                        
                                    key = f"{s_name} ({s_type})"
                                    stats["sensors"][h_name][key] = round(float(sensor.Value), 2)

                        # Приоритезация GPU
                        gpus = []
                        for h_name, s_data in stats["sensors"].items():
                            is_gpu = any(x in h_name.lower() for x in ["nvidia", "amd", "gpu"])
                            if is_gpu:
                                weight = 10
                                if "nvidia" in h_name.lower(): weight = 100
                                elif "rtx" in h_name.lower() or "gtx" in h_name.lower(): weight = 90
                                elif "radeon" in h_name.lower() and "graphics" not in h_name.lower(): weight = 80
                                gpus.append((weight, h_name, s_data))
                        
                        if gpus:
                            gpus.sort(key=lambda x: x[0], reverse=True)
                            best_weight, best_name, best_data = gpus[0]
                            stats["has_gpu"] = True
                            stats["gpu_name"] = best_name
                            for skey, sval in best_data.items():
                                if "core" in skey.lower() and "temperature" in skey.lower():
                                    stats["gpu_temp"] = sval
                                if "core" in skey.lower() and "load" in skey.lower():
                                    stats["gpu_load"] = sval
                            log(f"Selected GPU: {best_name}, Load: {stats['gpu_load']}, Temp: {stats['gpu_temp']}")

                        # Процессор
                        for h_name, s_data in stats["sensors"].items():
                            if "cpu" in h_name.lower() or "ryzen" in h_name.lower():
                                for skey, sval in s_data.items():
                                    # Берем Tctl/Tdie или Package как приоритетные, либо любое со словом Temperature если еще не нашли
                                    if any(x in skey for x in ["Package", "Tctl", "Tdie", "Core Max", "Core Average"]):
                                        stats["cpu_temp"] = sval
                                    elif stats["cpu_temp"] == 0 and "temperature" in skey.lower():
                                        stats["cpu_temp"] = sval

                    except Exception as e:
                        log(f"Update Loop Error: {e}")
                        stats["error"] = str(e)
                
                try:
                    json_str = json.dumps(stats)
                    data = json_str.encode('utf-8')
                    
                    if len(data) > SHMEM_SIZE - 1:
                        log(f"WARNING: Data too large ({len(data)} bytes), pruning sensors...")
                        # Удаляем детальные данные сенсоров, оставляем только главное
                        stats["sensors"] = {"pruned": "too_large"}
                        data = json.dumps(stats).encode('utf-8')
                    
                    data = data.ljust(SHMEM_SIZE, b'\x00')
                    self.shmem.seek(0)
                    self.shmem.write(data)
                except Exception as e:
                    log(f"Write SHMEM Error: {e}")
                
                time.sleep(1)
        except Exception as e:
            log(f"Fatal Error: {e}")
        finally:
            log("Helper shutting down...")
            if self.computer: self.computer.Close()
            if self.shmem: self.shmem.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--parent-pid", type=int, default=0)
    args = parser.parse_args()
    
    # Mutex
    mutex = ctypes.windll.kernel32.CreateMutexW(None, False, MUTEX_NAME)
    if ctypes.windll.kernel32.GetLastError() == 183:
        log("Mutex already held by another process, exiting.")
        sys.exit(0)

    if hasattr(sys, '_MEIPASS'):
        dll_path = os.path.join(sys._MEIPASS, "bin", "LibreHardwareMonitorLib.dll")
    else:
        script_path = os.path.abspath(__file__)
        dll_path = os.path.join(os.path.dirname(script_path), "bin", "LibreHardwareMonitorLib.dll")
    
    helper = SensorHelper(parent_pid=args.parent_pid)
    helper.run(dll_path)
