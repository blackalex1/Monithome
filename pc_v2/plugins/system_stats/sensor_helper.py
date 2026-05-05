import os
import sys
import json
import time
import ctypes
import mmap
import shutil
import argparse

def fix_venv_paths():
    try:
        venv_base = os.path.dirname(os.path.dirname(sys.executable))
        site_packages = os.path.join(venv_base, "Lib", "site-packages")
        if os.path.exists(site_packages) and site_packages not in sys.path:
            sys.path.append(site_packages)
    except:
        pass

def init_clr_and_dll(dll_path):
    try:
        import clr
        clr.AddReference(dll_path)
        from LibreHardwareMonitor.Hardware import Computer
        c = Computer()
        c.IsCpuEnabled = True
        c.IsGpuEnabled = True
        c.Open()
        return c
    except:
        return None

def is_process_running(pid):
    """Надежная проверка, жив ли родительский процесс."""
    if pid <= 0: return True
    try:
        # PROCESS_QUERY_LIMITED_INFORMATION (0x1000)
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        
        exit_code = ctypes.c_ulong()
        ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
        ctypes.windll.kernel32.CloseHandle(handle)
        
        # 259 = STILL_ACTIVE
        return exit_code.value == 259
    except:
        return False

SHMEM_NAME = "Local\\MonitHomeSensors"
SHMEM_SIZE = 1024
MUTEX_NAME = "Local\\MonitHomeSensorHelperMutex"

class SensorHelper:
    def __init__(self, parent_pid=0):
        self.computer = None
        self.shmem = None
        self._mutex = None
        self.parent_pid = parent_pid

    def is_admin(self):
        try:
            return ctypes.windll.shell32.IsUserAnAdmin() != 0
        except:
            return False

    def check_single_instance(self):
        self._mutex = ctypes.windll.kernel32.CreateMutexW(None, False, MUTEX_NAME)
        return ctypes.windll.kernel32.GetLastError() != 183

    def run(self, dll_path):
        self.computer = init_clr_and_dll(dll_path)
        try:
            self.shmem = mmap.mmap(-1, SHMEM_SIZE, tagname=SHMEM_NAME)
            while True:
                # ПРОВЕРКА РОДИТЕЛЯ: если основная программа закрылась, выходим
                if self.parent_pid > 0 and not is_process_running(self.parent_pid):
                    break

                stats = {"cpu_temp": 0, "gpu_temp": 0, "gpu_load": 0, "last_update": time.time()}
                if self.computer:
                    try:
                        for hardware in self.computer.Hardware:
                            hardware.Update()
                            if "Cpu" in str(hardware.HardwareType):
                                for s in hardware.Sensors:
                                    if "Temperature" in str(s.SensorType) and any(x in s.Name for x in ["Tctl", "Package", "Core"]):
                                        stats["cpu_temp"] = round(float(s.Value or 0), 1)
                            if "Gpu" in str(hardware.HardwareType):
                                for s in hardware.Sensors:
                                    if "Temperature" in str(s.SensorType): stats["gpu_temp"] = round(float(s.Value or 0), 1)
                                    if "Load" in str(s.SensorType): stats["gpu_load"] = round(float(s.Value or 0), 1)
                    except: pass
                
                data = json.dumps(stats).encode('utf-8').ljust(SHMEM_SIZE, b'\x00')
                self.shmem.seek(0)
                self.shmem.write(data)
                
                time.sleep(1)
        except:
            pass
        finally:
            if self.computer: self.computer.Close()
            if self.shmem: self.shmem.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--parent-pid", type=int, default=0)
    args = parser.parse_args()

    fix_venv_paths()
    script_path = os.path.abspath(__file__)
    python_exe = sys.executable
    # Путь к DLL (поддержка и скрипта, и скомпилированного EXE)
    if hasattr(sys, '_MEIPASS'):
        dll_path = os.path.join(sys._MEIPASS, "bin", "LibreHardwareMonitorLib.dll")
    else:
        dll_path = os.path.join(os.path.dirname(script_path), "bin", "LibreHardwareMonitorLib.dll")

    helper = SensorHelper(parent_pid=args.parent_pid)
    
    if not helper.is_admin():
        custom_exe_name = "MonitHome_Sensor_Helper.exe"
        custom_exe_path = os.path.join(os.path.dirname(python_exe), custom_exe_name)
        try:
            if not os.path.exists(custom_exe_path):
                shutil.copy2(python_exe, custom_exe_path)
            launcher = custom_exe_path
        except:
            launcher = python_exe

        # Передаем PID дальше по цепочке в админский процесс
        elevated_args = f'"{script_path}" --parent-pid {args.parent_pid}'
        ctypes.windll.shell32.ShellExecuteW(None, "runas", launcher, elevated_args, None, 0)
        sys.exit(0)

    if not helper.check_single_instance():
        sys.exit(0)

    helper.run(dll_path)
