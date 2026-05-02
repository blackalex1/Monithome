import os
import ctypes
import json
from base import BasePlugin

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
        super().__init__(socketio, config, manager)

    def get_wizard_data(self):
        """Метаданные для настройки кнопок системы"""
        return {
            "title": self.i18n("wizard_title"),
            "description": self.i18n("wizard_desc"),
            "items": [
                {"id": "lock", "label": self.i18n("lock"), "icon": "Lock"},
                {"id": "sleep", "label": self.i18n("sleep"), "icon": "Moon"},
                {"id": "restart", "label": self.i18n("restart"), "icon": "RefreshCw"},
                {"id": "shutdown", "label": self.i18n("shutdown"), "icon": "Power"}
            ]
        }

    def handle_wizard(self, selections):
        """Сохранение выбранных кнопок"""
        all_buttons = {
            "lock": { "label": self.i18n("lock_short"), "action": "lock", "icon": "Lock" },
            "sleep": { "label": self.i18n("sleep_short"), "action": "sleep", "icon": "Moon", "need_confirm": True },
            "restart": { "label": self.i18n("restart_short"), "action": "restart", "icon": "RefreshCw", "color": "text-yellow-500", "need_confirm": True },
            "shutdown": { "label": self.i18n("shutdown_short"), "action": "shutdown", "icon": "Power", "color": "text-red-500", "need_confirm": True }
        }
        selected_buttons = [all_buttons[b_id] for b_id in selections if b_id in all_buttons]
        
        self.save_config({
            "actions": [{"id": "system_buttons", "type": "button_group", "label": self.i18n("power_group"), "buttons": selected_buttons}]
        })

    def get_active_items(self):
        active = []
        for group in self.config.get("actions", []):
            for btn in group.get("buttons", []):
                active.append(btn["action"])
        return active

    def handle_command(self, sid, target, action, data=None):
        # Базовая обработка (мастер настройки)
        if super().handle_command(sid, target, action, data):
            return

        self.log(f"Executing system command: {action}")
        
        if action == 'lock':
            ctypes.windll.user32.LockWorkStation()
        elif action == 'sleep':
            os.system("powershell -Command \"Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false)\"")
        elif action == 'restart':
            os.system("shutdown /r /t 5 /f")
        elif action == 'shutdown':
            os.system("shutdown /s /t 5 /f")

