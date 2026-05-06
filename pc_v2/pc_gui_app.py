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

class CustomTitleBar(QWidget):
    def __init__(self, parent):
        super().__init__(parent)
        self.parent = parent
        self.setFixedHeight(48)
        self.setObjectName("titleBar")
        
        layout = QHBoxLayout(self)
        layout.setContentsMargins(18, 0, 0, 0)
        layout.setSpacing(0)
        
        # Left Side (Icon)
        self.icon_label = QLabel()
        if os.path.exists(parent.icon_path):
            self.icon_label.setPixmap(QIcon(parent.icon_path).pixmap(26, 26))
        layout.addWidget(self.icon_label)
        
        layout.addStretch()
        
        # Center Side (Title)
        self.title_label = QLabel("MonitHome Dashboard")
        self.title_label.setStyleSheet("font-weight: 700; color: #94A3B8; font-size: 12px; letter-spacing: 1px; text-transform: uppercase;")
        self.title_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.title_label)
        
        layout.addStretch()
        
        # Right Side (Buttons)
        self.btn_min = QPushButton("─")
        self.btn_max = QPushButton("▢")
        self.btn_close = QPushButton("✕")
        
        for btn in [self.btn_min, self.btn_max, self.btn_close]:
            btn.setFixedSize(50, 48)
            btn.setCursor(Qt.PointingHandCursor)
            layout.addWidget(btn)
            
        self.btn_min.setObjectName("btnMin")
        self.btn_max.setObjectName("btnMax")
        self.btn_close.setObjectName("btnClose")
        
        self.btn_min.clicked.connect(self.parent.showMinimized)
        self.btn_max.clicked.connect(self.toggle_maximize)
        self.btn_close.clicked.connect(self.parent.close)
        
        self.setStyleSheet("""
            #titleBar {
                background-color: #020617;
                border: none;
            }
            QLabel {
                border: none;
                background: transparent;
            }
            QPushButton {
                background: transparent;
                color: #64748B;
                border: none;
                font-size: 16px;
                font-weight: bold;
            }
            QPushButton#btnMax {
                font-size: 20px;
            }
            QPushButton#btnMin:hover, QPushButton#btnMax:hover {
                background-color: rgba(255, 255, 255, 0.05);
                color: white;
            }
            QPushButton#btnClose:hover {
                background-color: #ef4444;
                color: white;
            }
        """)

        self.start_pos = None

    def toggle_maximize(self):
        if self.parent.isMaximized():
            self.parent.showNormal()
            self.btn_max.setText("▢")
        else:
            self.parent.showMaximized()
            self.btn_max.setText("❐")

    def mouseDoubleClickEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.toggle_maximize()
            event.accept()

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.start_pos = event.globalPosition().toPoint() - self.parent.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event):
        if self.start_pos:
            self.parent.move(event.globalPosition().toPoint() - self.start_pos)
            event.accept()

    def mouseReleaseEvent(self, event):
        self.start_pos = None
        event.accept()

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
        self.setWindowFlags(Qt.FramelessWindowHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        
        # Main layout
        main_widget = QWidget()
        main_widget.setObjectName("mainContainer")
        self.setCentralWidget(main_widget)
        self.main_layout = QVBoxLayout(main_widget)
        self.main_layout.setContentsMargins(0, 0, 0, 0)
        self.main_layout.setSpacing(0)
        
        # Custom Title Bar
        self.title_bar = CustomTitleBar(self)
        self.main_layout.addWidget(self.title_bar)
        
        # Browser
        self.browser = QWebEngineView()
        self.main_layout.addWidget(self.browser)
        
        # Container style
        main_widget.setStyleSheet("""
            #mainContainer {
                background-color: #020617;
                border: 1px solid rgba(255, 255, 255, 0.1);
                border-radius: 12px;
            }
            #titleBar, QWebEngineView, QLabel {
                border: none;
                background-color: transparent;
            }
        """)
        
        self.setStyleSheet("background-color: #020617;")
        self.target_url = "http://127.0.0.1:5000/"
        
        # Установка куки с токеном ДО загрузки страницы
        from PySide6.QtNetwork import QNetworkCookie
        from PySide6.QtCore import QByteArray
        
        cookie = QNetworkCookie(QByteArray(b"gui_token"), QByteArray(config_manager.gui_token.encode()))
        cookie.setDomain("127.0.0.1")
        cookie.setPath("/")
        self.browser.page().profile().cookieStore().setCookie(cookie)
        
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
        """Полное и агрессивное завершение всех процессов"""
        logger.info("--- SHUTDOWN SEQUENCE STARTED ---")
        
        try:
            self.tray_icon.hide()
            QApplication.processEvents()
        except: pass
        
        # 1. Пытаемся корректно остановить плагины (с лимитом времени)
        try:
            from plugin_engine.manager import plugin_manager
            import asyncio
            logger.info("Stopping plugins...")
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            try:
                loop.run_until_complete(asyncio.wait_for(plugin_manager.shutdown(), timeout=1.5))
                logger.info("Plugins stopped successfully.")
            except asyncio.TimeoutError:
                logger.warning("Plugin shutdown timed out. Proceeding to force kill.")
            except Exception as e:
                logger.error(f"Error during plugin shutdown: {e}")
            finally:
                loop.close()
        except Exception as e:
            logger.error(f"Failed to access plugin manager: {e}")

        # 2. Убиваем все дочерние процессы (хелперы, сканеры и т.д.)
        try:
            import psutil
            current_process = psutil.Process()
            children = current_process.children(recursive=True)
            if children:
                logger.info(f"Terminating {len(children)} child processes...")
                for child in children:
                    try:
                        logger.info(f"Killing child PID {child.pid} ({child.name()})")
                        child.kill()
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        pass
        except Exception as e:
            logger.error(f"Error while killing children: {e}")

        logger.info("Final exit signal sent. Goodbye!")
        # Даем логам секунду записаться
        time.sleep(0.1)
        
        # Максимально агрессивное завершение
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
    
    is_minimized = "--minimized" in sys.argv
    if not is_minimized:
        window.show()
    else:
        logger.info("Starting minimized to tray.")
    
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
