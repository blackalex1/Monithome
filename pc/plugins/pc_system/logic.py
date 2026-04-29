import os
import ctypes

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config

    def get_wizard_data(self):
        """Метаданные для настройки кнопок системы"""
        return {
            "title": "Системные команды",
            "description": "Выберите команды, которые будут доступны на планшете.",
            "items": [
                {"id": "lock", "label": "Блокировка", "icon": "Lock"},
                {"id": "sleep", "label": "Режим сна", "icon": "Moon"},
                {"id": "restart", "label": "Перезагрузка", "icon": "RefreshCw"},
                {"id": "shutdown", "label": "Выключение", "icon": "Power"}
            ]
        }

    def handle_wizard(self, selections):
        """Сохранение выбранных кнопок"""
        import json
        all_buttons = {
            "lock": { "label": "Блок.", "action": "lock", "icon": "Lock" },
            "sleep": { "label": "Сон", "action": "sleep", "icon": "Moon", "need_confirm": True },
            "restart": { "label": "Рестарт", "action": "restart", "icon": "RefreshCw", "color": "text-yellow-500", "need_confirm": True },
            "shutdown": { "label": "Выкл.", "action": "shutdown", "icon": "Power", "color": "text-red-500", "need_confirm": True }
        }
        selected_buttons = [all_buttons[b_id] for b_id in selections if b_id in all_buttons]
        new_config = {
            "id": "pc_system", "name": "Система",
            "actions": [{"id": "system_buttons", "type": "button_group", "label": "Питание и сессия", "buttons": selected_buttons}]
        }
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        with open(config_path, "w", encoding="utf-8") as f:
            json.dump(new_config, f, indent=2, ensure_ascii=False)

    def get_active_items(self):
        """Возвращает список ID активных действий"""
        active = []
        # Ищем ID кнопок в конфигурации
        for group in self.config.get("actions", []):
            for btn in group.get("buttons", []):
                active.append(btn["action"])
        return active

    def handle_command(self, target, action):
        # get_wizard теперь обрабатывается в pc_agent.py автоматически
        if action == 'lock':
            ctypes.windll.user32.LockWorkStation()
        elif action == 'sleep':
            os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
        elif action == 'restart':
            os.system("shutdown /r /t 5")
        elif action == 'shutdown':
            os.system("shutdown /s /t 5")

