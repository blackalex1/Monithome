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
            "title": "Системные команды",
            "description": "Выберите команды для управления ПК.",
            "items": [
                {"id": "lock", "label": "Блокировка", "icon": "Lock"},
                {"id": "sleep", "label": "Режим сна", "icon": "Moon"},
                {"id": "restart", "label": "Перезагрузка", "icon": "RefreshCw"},
                {"id": "shutdown", "label": "Выключение", "icon": "Power"}
            ]
        }

    def handle_wizard(self, selections):
        """Сохранение выбранных кнопок"""
        all_buttons = {
            "lock": { "label": "Блок.", "action": "lock", "icon": "Lock" },
            "sleep": { "label": "Сон", "action": "sleep", "icon": "Moon", "need_confirm": True },
            "restart": { "label": "Рестарт", "action": "restart", "icon": "RefreshCw", "color": "text-yellow-500", "need_confirm": True },
            "shutdown": { "label": "Выкл.", "action": "shutdown", "icon": "Power", "color": "text-red-500", "need_confirm": True }
        }
        selected_buttons = [all_buttons[b_id] for b_id in selections if b_id in all_buttons]
        self.config.update({
            "actions": [{"id": "system_buttons", "type": "button_group", "label": "Питание и сессия", "buttons": selected_buttons}]
        })
        
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(self.config, f, indent=2, ensure_ascii=False)
        
        self.manager.broadcast_ui()

    def get_active_items(self):
        active = []
        for group in self.config.get("actions", []):
            for btn in group.get("buttons", []):
                active.append(btn["action"])
        return active

    def handle_command(self, target, action, data=None):
        if action == "get_wizard":
            self.manager.emit_to_plugin_ui(self.p_id, "wizard_data", self.get_wizard_data())
            return
        elif action in ["handle_wizard", "save_wizard", "save_settings", "update_config"]:
            selections = []
            if isinstance(data, list):
                selections = data
            elif isinstance(data, dict):
                selections = data.get("selections") or data.get("data") or data.get("items") or []
            self.handle_wizard(selections)
            return

        if action == 'lock':
            ctypes.windll.user32.LockWorkStation()
        elif action == 'sleep':
            os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
        elif action == 'restart':
            os.system("shutdown /r /t 5")
        elif action == 'shutdown':
            os.system("shutdown /s /t 5")

