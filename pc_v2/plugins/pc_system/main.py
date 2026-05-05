import os
import ctypes
import asyncio
import subprocess
from plugin_engine.base_plugin import BasePlugin

class Plugin(BasePlugin):
    """
    Плагин управления питанием ПК (pc_system v2).
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)

    async def on_start(self):
        self.log("pc_system started.")
        # Этот плагин только принимает команды, ему не нужен фоновый цикл
        pass

    async def on_stop(self):
        self.log("pc_system stopped.")
        pass

    async def handle_command(self, action: str, data: any):
        self.log(f"Executing system command: {action}")
        
        # Запускаем системные вызовы в отдельном потоке, 
        # чтобы os.system не заблокировал event loop (особенно при sleep)
        await asyncio.to_thread(self._execute_system_command, action)

    def _execute_system_command(self, action: str):
        # Используем subprocess.Popen с флагом скрытия окна, чтобы не было мерцания
        flags = 0x08000000 # CREATE_NO_WINDOW
        
        if action == 'lock':
            ctypes.windll.user32.LockWorkStation()
        elif action == 'sleep':
            cmd = ["powershell", "-Command", "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)"]
            subprocess.Popen(cmd, creationflags=flags)
        elif action == 'restart':
            subprocess.Popen(["shutdown", "/r", "/t", "5", "/f"], creationflags=flags)
        elif action == 'shutdown':
            subprocess.Popen(["shutdown", "/s", "/t", "5", "/f"], creationflags=flags)
        elif action == 'handle_wizard':
            # Заглушка: обработка сохранения кнопок из мастера настройки
            self.log(f"Handling wizard selections: {action}")
