import sys
import threading
import time
import logging
import ctypes
import uvicorn

# Специальная обработка для запуска сканера медиа внутри EXE
if len(sys.argv) > 1 and "media_scanner.py" in sys.argv[1]:
    try:
        # Добавляем пути, чтобы импорты внутри сканера работали
        base_path = os.path.dirname(os.path.abspath(__file__))
        sys.path.insert(0, base_path)
        sys.path.insert(0, os.path.join(base_path, "plugins", "pc_media"))
        
        import plugins.pc_media.media_scanner as scanner
        scanner.run_scanner() # Предполагаем, что там есть функция run_scanner() или main()
        sys.exit(0)
    except Exception as e:
        print(f"Scanner boot error: {e}")
        sys.exit(1)

try:
    from PySide6.QtWidgets import (
        QApplication, QMainWindow, QVBoxLayout, QWidget, QMessageBox, 
        QDialog, QLabel, QPushButton, QFrame, QHBoxLayout,
        QSystemTrayIcon, QMenu
    )
    from PySide6.QtWebEngineWidgets import QWebEngineView
    from PySide6.QtCore import QUrl, Signal, QObject, Slot, Qt, QTimer
    from PySide6.QtGui import QIcon, QAction
except ImportError:
    print("PySide6 is not installed. Please run: pip install PySide6")
    sys.exit(1)

# Добавляем путь к корню pc_v2, если запускаем скрипт напрямую
import os
import sys
import json

# Фикс для библиотек, которые падают без stdout/stderr в режиме --noconsole
if sys.stdout is None: sys.stdout = open(os.devnull, "w")
if sys.stderr is None: sys.stderr = open(os.devnull, "w")
if sys.stdin is None: sys.stdin = open(os.devnull, "r")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Импортируем ASGI-приложение и конфиг из main.py и config.py
from main import socket_app
from core.config import config_manager
from core.event_bus import event_bus

import json

class Translator:
    def __init__(self):
        self.lang = config_manager.config.language
        self.strings = {}
        self._load_translations()

    def _load_translations(self):
        try:
            # Скрипт лежит в /pc_v2/ (или распакован в _MEIPASS)
            # Языки лежат в /pc_v2/web/languages/
            base_path = os.path.dirname(os.path.abspath(__file__))
            lang_file = os.path.join(base_path, "web", "languages", f"{self.lang}.json")
            
            if os.path.exists(lang_file):
                with open(lang_file, "r", encoding="utf-8") as f:
                    self.strings = json.load(f)
            else:
                logger.warning(f"Language file {lang_file} not found")
        except Exception as e:
            logger.error(f"Failed to load translations: {e}")

    def tr(self, key, default):
        return self.strings.get(key, default)

translator = Translator()

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] GUI: %(message)s")
logger = logging.getLogger("GUI")

class PairingBridge(QObject):
    pairing_requested = Signal(str)
    hide_pairing_requested = Signal()

    def __init__(self):
        super().__init__()
        event_bus.subscribe("show_pairing_code", self._on_pairing_event)
        event_bus.subscribe("hide_pairing_code", self._on_hide_event)

    def _on_pairing_event(self, data):
        code = data.get("code", "0000")
        logger.info(f"Pairing request received in GUI: {code}")
        self.pairing_requested.emit(code)

    def _on_hide_event(self, data):
        logger.info("Pairing code hidden by server")
        self.hide_pairing_requested.emit()

class FileDialogBridge(QObject):
    file_selected = Signal(str, str)

    def __init__(self):
        super().__init__()
        event_bus.subscribe("request_file_dialog", self._on_request_dialog)

    def _on_request_dialog(self, data):
        # Это будет вызвано из потока сервера, поэтому только логируем и сигналим в GUI поток
        logger.info("File dialog requested by plugin")
        # Мы не можем вызвать QFileDialog прямо здесь, поэтому используем сигнал, 
        # которыйMainWindow поймает в своем потоке.
        # Но проще использовать QTimer.singleShot или просто заэмитить сигнал.
        self.file_selected.emit(data.get("plugin_id", ""), data.get("title", "Select File"))

class PairingDialog(QDialog):
    def __init__(self, code, parent=None):
        super().__init__(parent)
        self.setWindowTitle("New Connection")
        self.setWindowFlags(self.windowFlags() | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        
        self.container = QFrame(self)
        self.container.setObjectName("dialogContainer")
        self.container.setFixedSize(450, 320)
        
        theme_color = "#22C55E"
        bg_color = "#020617"
        
        self.container.setStyleSheet(f"""
            #dialogContainer {{
                background-color: {bg_color};
                border: 1px solid {theme_color}55;
                border-radius: 30px;
            }}
            QLabel {{
                color: white;
                font-family: 'Segoe UI', 'Roboto', sans-serif;
            }}
            #titleLabel {{
                font-size: 24px;
                font-weight: 800;
                letter-spacing: 1px;
                color: white;
            }}
            #descLabel {{
                color: #94A3B8;
                font-size: 13px;
                font-weight: 500;
                letter-spacing: 0.5px;
            }}
            #codeLabel {{
                color: {theme_color};
                font-size: 72px;
                font-weight: 900;
                letter-spacing: 6px;
                margin: 10px 0px;
            }}
            QPushButton {{
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 {theme_color}, stop:1 #10B981);
                color: #020617;
                border-radius: 15px;
                font-weight: 900;
                font-size: 13px;
                padding: 12px 40px;
                border: none;
                letter-spacing: 1px;
            }}
            QPushButton:hover {{
                background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #4ADE80, stop:1 {theme_color});
            }}
        """)

        layout = QVBoxLayout(self.container)
        layout.setContentsMargins(40, 40, 40, 40)
        layout.setSpacing(0)

        # Заголовок
        title = QLabel(translator.tr("pairing_title", "DEVICE PAIRING"))
        title.setObjectName("titleLabel")
        title.setAlignment(Qt.AlignCenter)
        layout.addWidget(title)

        layout.addSpacing(10)

        desc = QLabel(translator.tr("pairing_desc", "Enter this security code on your tablet"))
        desc.setObjectName("descLabel")
        desc.setAlignment(Qt.AlignCenter)
        layout.addWidget(desc)

        layout.addStretch()

        # Код с эффектом свечения (через стиль)
        code_lbl = QLabel(code)
        code_lbl.setObjectName("codeLabel")
        code_lbl.setAlignment(Qt.AlignCenter)
        layout.addWidget(code_lbl)

        layout.addStretch()

        # Кнопка
        btn = QPushButton(translator.tr("btn_dismiss", "DISMISS"))
        btn.setCursor(Qt.PointingHandCursor)
        btn.clicked.connect(self.accept)
        
        btn_layout = QHBoxLayout()
        btn_layout.addStretch()
        btn_layout.addWidget(btn)
        btn_layout.addStretch()
        layout.addLayout(btn_layout)

        main_layout = QVBoxLayout(self)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.addWidget(self.container)

    def update_code(self, code):
        """Обновление текста кода в уже открытом окне"""
        code_lbl = self.findChild(QLabel, "codeLabel")
        if code_lbl:
            code_lbl.setText(code)

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MonitHome Dashboard")
        
        # Путь к иконке (используем абсолютный BUNDLE_DIR из конфига)
        from core.config import BUNDLE_DIR
        self.icon_path = os.path.join(BUNDLE_DIR, "web", "favicon.png")
        if os.path.exists(self.icon_path):
            self.setWindowIcon(QIcon(self.icon_path))
        
        self.resize(1280, 800)
        
        self.browser = QWebEngineView()
        
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.browser)
        
        container = QWidget()
        container.setLayout(layout)
        self.setCentralWidget(container)
        
        self.setStyleSheet("background-color: #020617;")
        self.target_url = f"http://127.0.0.1:5000/?gui_token={config_manager.gui_token}"
        self.browser.setUrl(QUrl(self.target_url))
        
        # Обработка ошибок загрузки (например, если сервер еще не встал)
        self.browser.loadFinished.connect(self._on_load_finished)
        
        self.setup_tray()

    def setup_tray(self):
        """Настройка системного трея"""
        self.tray_icon = QSystemTrayIcon(self)
        
        if os.path.exists(self.icon_path):
            self.tray_icon.setIcon(QIcon(self.icon_path))
        else:
            self.tray_icon.setIcon(self.style().standardIcon(self.style().SP_ComputerIcon))
            
        # Меню трея
        tray_menu = QMenu()
        
        show_action = QAction(translator.tr("tray_open", "Open Dashboard"), self)
        show_action.triggered.connect(self.show_normal)
        
        quit_action = QAction(translator.tr("tray_exit", "Exit MonitHome"), self)
        quit_action.triggered.connect(self.quit_application)
        
        tray_menu.addAction(show_action)
        tray_menu.addSeparator()
        tray_menu.addAction(quit_action)
        
        self.tray_icon.setContextMenu(tray_menu)
        self.tray_icon.show()
        
        # Клик по иконке
        self.tray_icon.activated.connect(self._on_tray_activated)

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.Trigger: # Одинарный клик
            if self.isVisible():
                self.hide()
            else:
                self.show_normal()

    def show_normal(self):
        self.show()
        self.activateWindow()
        self.raise_()

    def _on_load_finished(self, success):
        if not success:
            logger.warning("Page load failed, retrying in 2 seconds...")
            QTimer.singleShot(2000, lambda: self.browser.setUrl(QUrl(self.target_url)))

    def closeEvent(self, event):
        """Перехват закрытия окна: прячем в трей"""
        if self.tray_icon.isVisible():
            self.hide()
            event.ignore()
        else:
            self.quit_application()

    def quit_application(self):
        """Полное завершение всех процессов"""
        logger.info("Shutting down application...")
        self.tray_icon.hide()
        
        # Даем Qt время скрыть иконку
        QApplication.processEvents()
        
        try:
            from plugin_engine.manager import plugin_manager
            import asyncio
            # Даем плагинам шанс корректно остановиться (с лимитом 2 секунды)
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            try:
                loop.run_until_complete(asyncio.wait_for(plugin_manager.shutdown(), timeout=2.0))
            except asyncio.TimeoutError:
                logger.warning("Shutdown timed out, forcing exit...")
            finally:
                loop.close()
        except Exception as e:
            logger.error(f"Error during shutdown: {e}")
        
        logger.info("Final exit.")
        # Максимально агрессивное завершение, чтобы убить все фоновые потоки и хелперы
        os._exit(0) 

    def show_pairing_code(self, code):
        """Показ кода сопряжения. Если окно уже открыто - просто обновляем код."""
        if hasattr(self, 'pairing_dialog') and self.pairing_dialog:
            self.pairing_dialog.update_code(code)
            self.pairing_dialog.show() # На всякий случай выводим на передний план
            self.pairing_dialog.raise_()
            self.pairing_dialog.activateWindow()
        else:
            self.pairing_dialog = PairingDialog(code, self)
            self.pairing_dialog.show()

    def hide_pairing_code(self):
        if hasattr(self, 'pairing_dialog') and self.pairing_dialog:
            self.pairing_dialog.close()
            self.pairing_dialog = None

    @Slot(str, str)
    def open_system_file_dialog(self, plugin_id, title):
        from PySide6.QtWidgets import QFileDialog
        file_path, _ = QFileDialog.getOpenFileName(
            self, title, "", "Executables (*.exe *.lnk);;All Files (*.*)"
        )
        if file_path:
            # Возвращаем путь обратно в систему через EventBus
            file_path = os.path.normpath(file_path)
            # Извлекаем название из имени файла
            label = os.path.splitext(os.path.basename(file_path))[0].replace("_", " ").title()
            
            # Используем потокобезопасный метод шины событий
            event_bus.emit_threadsafe("plugin_custom_event", {
                "plugin_id": plugin_id,
                "event": "file_selected",
                "data": {"path": file_path, "label": label}
            })

def kill_process_on_port(port):
    """Быстро находит и убивает процесс на порту, исключая текущий процесс"""
    import psutil
    import os
    try:
        my_pid = os.getpid()
        for conn in psutil.net_connections(kind='inet'):
            if conn.laddr.port == port and conn.status == 'LISTEN':
                pid = conn.pid
                if pid and pid != my_pid:
                    try:
                        proc = psutil.Process(pid)
                        logger.info(f"Killing stale process {proc.name()} (PID: {pid}) on port {port}")
                        proc.kill()
                        proc.wait(timeout=2) # Ждем завершения
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        pass
    except Exception as e:
        logger.debug(f"Silent port check error: {e}")

def run_uvicorn():
    logger.info("Starting Async FastAPI Server in background thread...")
    # Даем небольшой зазор перед стартом uvicorn
    time.sleep(0.5)
    try:
        # Запускаем uvicorn без автоперезагрузки в фоновом потоке
        uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="warning")
    except Exception as e:
        logger.error(f"Failed to start server: {e}")

def hide_console():
    """Скрывает окно консоли на Windows (если не запущено через pythonw)"""
    if sys.platform == "win32":
        try:
            kernel32 = ctypes.WinDLL('kernel32')
            user32 = ctypes.WinDLL('user32')
            hWnd = kernel32.GetConsoleWindow()
            if hWnd:
                user32.ShowWindow(hWnd, 0) # SW_HIDE = 0
        except Exception as e:
            logger.error(f"Failed to hide console: {e}")

def main():
    hide_console()
    
    # 0. Инициализация плагинов (без блокировки на правах админа)
    import asyncio
    from plugin_engine.manager import plugin_manager
    # Мы больше не блокируем запуск сервера здесь.
    # Права будут запрошены плагинами по необходимости через GUI.
    
    # Пытаемся освободить порт, если он занят старым процессом
    kill_process_on_port(5000)
    
    # 1. Запуск асинхронного сервера в отдельном потоке
    server_thread = threading.Thread(target=run_uvicorn, daemon=True)
    server_thread.start()

    # 2. Ждем, пока сервер реально начнет отвечать
    import socket
    def is_server_ready(port):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                return s.connect_ex(('127.0.0.1', port)) == 0
        except: return False

    logger.info("Waiting for server to start...")
    for _ in range(20): # Максимум 10 секунд ожидания
        if is_server_ready(5000): break
        time.sleep(0.5)

    # 3. Запуск Qt GUI в главном потоке
    logger.info("Starting Desktop GUI...")
    qt_app = QApplication(sys.argv)
    qt_app.setApplicationName("MonitHome")
    
    bridge = PairingBridge()
    file_bridge = FileDialogBridge()
    window = MainWindow()
    bridge.pairing_requested.connect(window.show_pairing_code)
    bridge.hide_pairing_requested.connect(window.hide_pairing_code)
    file_bridge.file_selected.connect(window.open_system_file_dialog)
    
    window.show()
    
    sys.exit(qt_app.exec())

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        import traceback
        with open("crash.log", "a", encoding="utf-8") as f:
            f.write(f"\n--- CRASH AT {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n")
            f.write(str(e) + "\n")
            f.write(traceback.format_exc())
        print(f"CRITICAL ERROR: {e}. See crash.log for details.")
        input("Press Enter to exit...") # Не даем окну закрыться, если это консоль
