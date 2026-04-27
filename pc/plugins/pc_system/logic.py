import os
import ctypes

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config

    def handle_command(self, target, action):
        print(f"PC System Command: {action}")
        if action == 'lock':
            ctypes.windll.user32.LockWorkStation()
        elif action == 'sleep':
            os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
        elif action == 'restart':
            os.system("shutdown /r /t 5")
        elif action == 'shutdown':
            os.system("shutdown /s /t 5")
