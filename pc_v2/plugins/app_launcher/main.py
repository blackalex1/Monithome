import os
import asyncio
import subprocess
from plugin_engine.base_plugin import BasePlugin

class Plugin(BasePlugin):
    """
    Плагин для запуска приложений на ПК.
    """
    def __init__(self, plugin_id: str):
        super().__init__(plugin_id)

    async def on_start(self):
        self.log("App Launcher started.")
        # Обновляем иконки и рассылаем состояние
        asyncio.create_task(self._auto_update_icons())
        await self._emit_launcher_state()

    def _open_file_dialog(self):
        try:
            import tkinter as tk
            from tkinter import filedialog
            import os

            # Создаем скрытое окно tkinter
            root = tk.Tk()
            root.withdraw()
            root.attributes("-topmost", True) # Поверх всех окон

            file_path = filedialog.askopenfilename(
                title="Выберите приложение для добавления",
                filetypes=[
                    ("Executables", "*.exe"),
                    ("Links", "*.lnk"),
                    ("All Files", "*.*")
                ]
            )
            
            root.destroy()
            return file_path
        except Exception as e:
            self.log(f"Tkinter dialog failed: {e}", 30)
            return ""

    async def _emit_launcher_state(self):
        config = self.get_config()
        buttons = []
        for widget in config.get("widgets", []):
            if widget.get("type") == "button_group":
                buttons = widget.get("buttons", [])
                break
        await self.emit_state({"buttons": buttons})

    async def on_stop(self):
        self.log("App Launcher stopped.")
        pass

    async def _auto_update_icons(self):
        config = self.get_config()
        changed = False
        for widget in config.get("widgets", []):
            if widget.get("type") == "button_group":
                for btn in widget.get("buttons", []):
                    # Если иконка не установлена или это просто текстовое имя иконки (не base64)
                    icon = btn.get("icon", "")
                    if not icon or (len(icon) < 50 and icon != "Default"):
                        icon_b64 = await asyncio.to_thread(self._extract_icon, btn.get("data"))
                        if icon_b64:
                            btn["icon"] = icon_b64
                            changed = True
        if changed:
            self.save_config(config)
            await self._emit_launcher_state()

    def _extract_icon(self, path: str):
        try:
            import sys
            import shutil
            import base64
            from PySide6.QtWidgets import QApplication, QFileIconProvider
            from PySide6.QtCore import QFileInfo, QBuffer, QIODevice
            from PySide6.QtGui import QIcon, QPixmap

            # Если путь не абсолютный, пробуем найти его в системе
            full_path = path
            if not os.path.isabs(path):
                found = shutil.which(path)
                if found:
                    full_path = found
                else:
                    # Пробуем стандартные места для Windows
                    common_paths = [
                        os.path.join(os.environ.get("ProgramFiles", "C:\\Program Files"), path),
                        os.path.join(os.environ.get("ProgramFiles(x86)", "C:\\Program Files (x86)"), path),
                        os.path.join(os.environ.get("LocalAppData", ""), "Programs", path)
                    ]
                    for p in common_paths:
                        if os.path.exists(p):
                            full_path = p
                            break
                        if os.path.exists(p + ".exe"):
                            full_path = p + ".exe"
                            break

            if not os.path.exists(full_path):
                return None

            # Нужен экземпляр QApplication (он обычно уже есть в процессе GUI)
            app = QApplication.instance()
            if app is None:
                # Если запускаем без GUI (только сервер), создаем временный
                app = QApplication(sys.argv)

            info = QFileInfo(full_path)
            provider = QFileIconProvider()
            icon = provider.icon(info)
            
            # Извлекаем пиксели в высоком разрешении (256x256)
            pixmap = icon.pixmap(256, 256)
            if pixmap.isNull():
                # Если 256 нет, пробуем 128
                pixmap = icon.pixmap(128, 128)
            
            if pixmap.isNull():
                return None

            buffer = QBuffer()
            buffer.open(QIODevice.WriteOnly)
            pixmap.save(buffer, "PNG")
            b64_data = base64.b64encode(buffer.data().data()).decode()
            
            return f"data:image/png;base64,{b64_data}"
        except Exception as e:
            # self.log(f"Icon extraction failed for {path}: {e}", 20)
            return None

    async def handle_command(self, action: str, data: any):
        if action == "launch":
            app_path = None
            if isinstance(data, dict):
                app_path = data.get("path") or data.get("value") or data.get("data")
            else:
                app_path = data
                
            # Если данные пришли в виде строки "{path=...}" (строковое представление Java Map)
            if isinstance(app_path, str) and app_path.startswith("{") and "=" in app_path:
                import re
                match = re.search(r"path=([^,}]+)", app_path) or re.search(r"value=([^,}]+)", app_path) or re.search(r"data=([^,}]+)", app_path)
                if match:
                    app_path = match.group(1).strip()
                else:
                    # Если регулярка не сработала, пробуем грубо очистить
                    app_path = app_path.split("=")[-1].rstrip("}")
                
            self.log(f"Attempting to launch: {app_path}")
            if app_path:
                await asyncio.to_thread(self._launch_app, app_path)
            else:
                self.log("Launch aborted: No path provided", 30)
            
        elif action == "browse_file":
            path = await asyncio.to_thread(self._open_file_dialog)
            if path:
                # Пытаемся также угадать название приложения из пути
                label = os.path.splitext(os.path.basename(path))[0].title()
                await self.emit_event("file_selected", {"path": path, "label": label})

        elif action == "add_app":
            config = self.get_config()
            app_path = data.get("path", "")
            
            # Пытаемся достать иконку автоматически
            icon_b64 = await asyncio.to_thread(self._extract_icon, app_path)
            
            new_button = {
                "label": data.get("label", "New App"),
                "action": "launch",
                "data": app_path,
                "icon": icon_b64 or data.get("icon", "AppWindow")
            }
            
            for widget in config.get("widgets", []):
                if widget.get("type") == "button_group":
                    widget.setdefault("buttons", []).append(new_button)
                    break
            
            self.save_config(config)
            await self._emit_launcher_state()
            await self.emit_event("config_updated", {"success": True})

        elif action == "remove_app":
            # data может быть строкой или словарем {"value": "Label"} из-за особенностей сокет-хендлера
            label = data.get("value") if isinstance(data, dict) else data
            config = self.get_config()
            removed = False
            for widget in config.get("widgets", []):
                if widget.get("type") == "button_group":
                    original_count = len(widget.get("buttons", []))
                    widget["buttons"] = [b for b in widget["buttons"] if b.get("label") != label]
                    if len(widget["buttons"]) < original_count:
                        removed = True
            
            if removed:
                self.save_config(config)
                self.log(f"Removed app: {label}")
                await self._emit_launcher_state()
                await self.emit_event("config_updated", {"success": True})

        elif action == "move_app":
            # data: {"label": "Name", "direction": "up"|"down"}
            label = data.get("label")
            direction = data.get("direction")
            config = self.get_config()
            
            for widget in config.get("widgets", []):
                if widget.get("type") == "button_group":
                    buttons = widget.get("buttons", [])
                    idx = next((i for i, b in enumerate(buttons) if b.get("label") == label), -1)
                    if idx != -1:
                        if direction == "up" and idx > 0:
                            buttons[idx], buttons[idx-1] = buttons[idx-1], buttons[idx]
                        elif direction == "down" and idx < len(buttons) - 1:
                            buttons[idx], buttons[idx+1] = buttons[idx+1], buttons[idx]
                        
                        self.save_config(config)
                        await self._emit_launcher_state()
                        await self.emit_event("config_updated", {"success": True})
                    break

        elif action == "reorder_apps":
            # data: {"labels": ["Name1", "Name2", ...]}
            new_labels = data.get("labels", [])
            config = self.get_config()
            
            for widget in config.get("widgets", []):
                if widget.get("type") == "button_group":
                    current_buttons = widget.get("buttons", [])
                    # Создаем мапу для быстрого доступа
                    btn_map = {b.get("label"): b for b in current_buttons}
                    # Перестраиваем список в новом порядке
                    new_buttons = []
                    for label in new_labels:
                        if label in btn_map:
                            new_buttons.append(btn_map[label])
                    
                    # Добавляем те, что могли потеряться (на всякий случай)
                    for b in current_buttons:
                        if b.get("label") not in new_labels:
                            new_buttons.append(b)
                            
                    widget["buttons"] = new_buttons
                    self.save_config(config)
                    await self._emit_launcher_state()
                    await self.emit_event("config_updated", {"success": True})
                    break
        else:
            self.log(f"Unknown action: {action}")

    def _launch_app(self, path: str):
        try:
            # os.startfile — самый надежный способ на Windows запустить что угодно 
            # (exe, ссылку, папку, команду из PATH)
            os.startfile(path)
            self.log(f"Successfully launched: {path}")
        except Exception as e:
            self.log(f"Failed to launch {path}: {e}", level=30) # WARNING level
            
            # Попытка запустить через shell, если startfile не сработал
            try:
                subprocess.Popen(path, shell=True)
                self.log(f"Launched via shell: {path}")
            except Exception as e2:
                self.log(f"Second attempt failed: {e2}", level=40) # ERROR level
