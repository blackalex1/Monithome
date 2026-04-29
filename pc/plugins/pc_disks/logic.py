import psutil
import threading
import time
import os
import ctypes

class Plugin:
    def __init__(self, socketio, config, manager):
        self.socketio = socketio
        self.config = config
        self.manager = manager
        self._stop_event = threading.Event()
        self._stats_cache = {
            "plugin_id": "pc_disks",
            "disks": []
        }
        
        # Сразу получаем данные при инициализации
        self._stats_cache["disks"] = self._get_disks()
        
        self._thread = threading.Thread(target=self._stats_loop, daemon=True)
        self._thread.start()

    def stop(self):
        """Останавливает фоновый поток плагина"""
        self._stop_event.set()

    def _get_disks(self, filter_selected=True):
        disks = []
        try:
            # Получаем все разделы, исключая CD-ROM и пустые типы ФС
            for part in psutil.disk_partitions(all=False):
                if 'cdrom' in part.opts or part.fstype == '':
                    continue
                
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    
                    # Очищаем имя устройства для отображения (например, C: вместо C:\)
                    device = part.device.replace('\\', '')
                    if not device and part.mountpoint:
                        device = part.mountpoint.replace('\\', '')

                    # Пытаемся получить метку тома в Windows
                    label = ""
                    if os.name == 'nt':
                        try:
                            volumeNameBuffer = ctypes.create_unicode_buffer(1024)
                            fileSystemNameBuffer = ctypes.create_unicode_buffer(1024)
                            ctypes.windll.kernel32.GetVolumeInformationW(
                                ctypes.c_wchar_p(part.mountpoint),
                                volumeNameBuffer,
                                ctypes.sizeof(volumeNameBuffer),
                                None, None, None,
                                fileSystemNameBuffer,
                                ctypes.sizeof(fileSystemNameBuffer)
                            )
                            label = volumeNameBuffer.value
                        except:
                            pass

                    # Фильтрация, если есть настройки выбранных дисков
                    if filter_selected:
                        selected = self.config.get("selected_disks", [])
                        if selected and device not in selected:
                            continue

                    disks.append({
                        "device": device,
                        "label": label or "Локальный диск",
                        "total": round(usage.total / (1024**3), 1),
                        "used": round(usage.used / (1024**3), 1),
                        "free": round(usage.free / (1024**3), 1),
                        "percent": usage.percent
                    })
                except Exception as e:
                    # Некоторые диски могут быть заблокированы или недоступны
                    print(f"[DISKS] Error reading {part.mountpoint}: {e}")
                    
        except Exception as e:
            print(f"[DISKS] Error getting partitions: {e}")
            
        return disks

    def _stats_loop(self):
        while not self._stop_event.is_set():
            try:
                self._stats_cache["disks"] = self._get_disks()
                self.manager.broadcast_stats(self._stats_cache)
            except Exception as e:
                self.manager.log("DISKS", f"Loop error: {e}", level="error")
            
            # Диски меняются редко, опрашиваем раз в 2 минуты
            # Но поток просыпается чаще для проверки stop_event
            for _ in range(120):
                if self._stop_event.is_set(): break
                time.sleep(1)

    def get_stats(self):
        """Возвращает кэшированные данные (вызывается при подключении нового клиента)"""
        return self._stats_cache

    def get_wizard_data(self):
        """Возвращает данные для мастера настройки"""
        all_disks = self._get_disks(filter_selected=False)
        items = []
        for d in all_disks:
            items.append({
                "id": d["device"],
                "label": f"Диск {d['device']} ({d['label']})",
                "type": "checkbox"
            })
        
        return {
            "title": "Настройка дисков",
            "description": "Выберите локальные диски, которые вы хотите видеть в мониторинге.",
            "items": items
        }

    def handle_wizard(self, selections):
        """Сохраняет выбранные в мастере диски в config.json"""
        # В данном случае мы можем просто сохранить список разрешенных ID устройств
        # и фильтровать их в _get_disks. 
        # Но для простоты реализации по просьбе пользователя, 
        # мы можем добавить поле "filter" в конфиг.
        
        self.config["selected_disks"] = selections
        
        config_path = os.path.join(os.path.dirname(__file__), "config.json")
        with open(config_path, "w", encoding="utf-8") as f:
            import json
            json.dump(self.config, f, indent=2, ensure_ascii=False)
        
        # Обновляем кэш сразу
        self._stats_cache["disks"] = self._get_disks()
        self.socketio.emit('stats', self._stats_cache)

    def get_active_items(self):
        return self.config.get("selected_disks", [])

    def handle_command(self, target, action):
        """Обработка команд от клиента"""
        if action == "update_disks":
            self._stats_cache["disks"] = self._get_disks()
            self.socketio.emit('stats', self._stats_cache)
            # logger.info("[DISKS] Manual update triggered")
