import psutil
import threading
import time
import os
import ctypes
import json
from base import BasePlugin

class Plugin(BasePlugin):
    def __init__(self, socketio, config, manager):
        super().__init__(socketio, config, manager)
        self._stop_event = threading.Event()
        self._state = {"disks": []}
        
    def start(self):
        """Запуск фонового опроса дисков"""
        self.manager.subscribe("client_connected", self._on_client_connected)
        # Первый опрос сразу
        self._update_disks_state()
        threading.Thread(target=self._stats_loop, daemon=True).start()

    def _on_client_connected(self, sid):
        """Мгновенное обновление при подключении нового клиента"""
        self.log(f"New client {sid} connected, refreshing disks immediately")
        self._update_disks_state()

    def stop(self):
        self._stop_event.set()

    def _get_disks(self, filter_selected=True):
        disks = []
        try:
            for part in psutil.disk_partitions(all=False):
                if 'cdrom' in part.opts or part.fstype == '':
                    continue
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    device = part.device.replace('\\', '')
                    if not device and part.mountpoint:
                        device = part.mountpoint.replace('\\', '')

                    label = ""
                    if os.name == 'nt':
                        try:
                            volumeNameBuffer = ctypes.create_unicode_buffer(1024)
                            ctypes.windll.kernel32.GetVolumeInformationW(
                                ctypes.c_wchar_p(part.mountpoint),
                                volumeNameBuffer, ctypes.sizeof(volumeNameBuffer),
                                None, None, None, None, 0
                            )
                            label = volumeNameBuffer.value
                        except: pass

                    if filter_selected:
                        selected = self.config.get("selected_disks", [])
                        if selected and device not in selected: continue

                    disks.append({
                        "device": device,
                        "label": label or self.i18n("local_disk"),
                        "total": round(usage.total / (1024**3), 1),
                        "used": round(usage.used / (1024**3), 1),
                        "free": round(usage.free / (1024**3), 1),
                        "free_text": self.i18n("free_of").format(
                            free=round(usage.free / (1024**3), 1),
                            total=round(usage.total / (1024**3), 1)
                        ),
                        "percent": usage.percent
                    })
                except: pass
        except: pass
        return disks

    def _update_disks_state(self):
        # Пробуем получить диски несколько раз, если список пуст
        # (иногда системные вызовы возвращают пустой список при нагрузке)
        new_disks = []
        for attempt in range(3):
            new_disks = self._get_disks()
            if new_disks:
                break
            time.sleep(1)
            
        # Если диски пропали внезапно, но раньше были, 
        # даем системе еще один шанс в следующем цикле (не затираем сразу)
        if not new_disks and self._state.get("disks"):
            # Помечаем, что это временная потеря (можно логгировать)
            self.log("Warning: Disks temporarily missing, keeping previous state", level="warning")
            return

        self._state["disks"] = new_disks
        self.update_state(self._state)

    def _stats_loop(self):
        while not self._stop_event.is_set():
            try:
                self._update_disks_state()
            except Exception as e:
                self.log(f"Loop error: {e}", level="error")
            
            # Диски меняются не очень часто, но 120с было слишком много. Ставим 30с.
            for _ in range(30):
                if self._stop_event.is_set(): break
                time.sleep(1)

    def get_stats(self):
        return self._state

    def get_wizard_data(self):
        all_disks = self._get_disks(filter_selected=False)
        items = [{"id": d["device"], "label": f"{self.i18n('disk')} {d['device']} ({d['label']})", "type": "checkbox"} for d in all_disks]
        return {
            "title": self.i18n("wizard_title"),
            "description": self.i18n("wizard_desc"),
            "items": items
        }

    def handle_wizard(self, selections):
        self.save_config({"selected_disks": selections})
        self._update_disks_state()

    def get_active_items(self):
        return self.config.get("selected_disks", [])

    def handle_command(self, target, action, data=None):
        # Сначала даем базе обработать общие команды (мастер настройки)
        if super().handle_command(target, action, data):
            return

        if action == "update_disks":
            self._update_disks_state()
