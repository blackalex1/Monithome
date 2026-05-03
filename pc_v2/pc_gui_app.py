import sys
import threading
import time
import logging
import ctypes
import uvicorn

try:
    from PySide6.QtWidgets import QApplication, QMainWindow, QVBoxLayout, QWidget, QMessageBox
    from PySide6.QtWebEngineWidgets import QWebEngineView
    from PySide6.QtCore import QUrl, Signal, QObject, Slot
    from PySide6.QtGui import QIcon
except ImportError:
    print("PySide6 is not installed. Please run: pip install PySide6")
    sys.exit(1)

# Добавляем путь к корню pc_v2, если запускаем скрипт напрямую
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Импортируем ASGI-приложение и конфиг из main.py и config.py
from main import socket_app
from core.config import config_manager
from core.event_bus import event_bus

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] GUI: %(message)s")
logger = logging.getLogger("GUI")

class PairingBridge(QObject):
    pairing_requested = Signal(str)

    def __init__(self):
        super().__init__()
        event_bus.subscribe("show_pairing_code", self._on_pairing_event)

    def _on_pairing_event(self, data):
        code = data.get("code", "0000")
        logger.info(f"Pairing request received in GUI: {code}")
        self.pairing_requested.emit(code)

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MonitHome Dashboard")
        self.setWindowIcon(QIcon(os.path.join(os.path.dirname(__file__), "web", "favicon.png")))
        self.resize(1280, 800)
        
        self.browser = QWebEngineView()
        
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.browser)
        
        container = QWidget()
        container.setLayout(layout)
        self.setCentralWidget(container)
        
        self.setStyleSheet("background-color: #020617;")
        self.browser.setUrl(QUrl(f"http://127.0.0.1:5000/?gui_token={config_manager.gui_token}"))

    @Slot(str)
    def show_pairing_code(self, code):
        msg = QMessageBox(self)
        msg.setWindowTitle("New Device Connection")
        msg.setIcon(QMessageBox.Information)
        msg.setText(f"A new device is trying to connect.\n\nEnter this code on the device:")
        msg.setInformativeText(f"<b style='font-size: 24px; color: #22C55E;'>{code}</b>")
        msg.setStandardButtons(QMessageBox.Ok)
        msg.exec()

def kill_process_on_port(port):
    """Пытается найти и убить все процессы, занимающие порт, и ждет освобождения"""
    if sys.platform != "win32": return
    import subprocess
    import socket
    import time
    
    def is_port_in_use():
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(0.5)
            return s.connect_ex(('127.0.0.1', port)) == 0

    for attempt in range(5):
        if not is_port_in_use(): 
            if attempt > 0: logger.info(f"Port {port} is now free.")
            return
        
        try:
            # Ищем PID процесса по порту
            result = subprocess.check_output(f'netstat -aon | findstr :{port}', shell=True).decode()
            for line in result.strip().split('\n'):
                # Ищем именно LISTENING или любые активные соединения если порт всё еще занят
                if 'LISTENING' in line or (attempt > 0 and f':{port}' in line):
                    parts = line.strip().split()
                    pid = parts[-1]
                    if pid.isdigit() and pid != '0' and int(pid) != os.getpid():
                        logger.info(f"Killing process {pid} on port {port} (attempt {attempt+1})...")
                        subprocess.run(f'taskkill /F /PID {pid}', shell=True, capture_output=True)
            time.sleep(1.0)
        except Exception:
            time.sleep(1.0)
    
    if is_port_in_use():
        logger.warning(f"Could not free port {port} after several attempts.")

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
    
    # Пытаемся освободить порт, если он занят старым процессом
    kill_process_on_port(5000)
    
    # В будущем здесь можно добавить проверку прав администратора (ctypes.windll.shell32.IsUserAnAdmin)
    
    # 1. Запуск асинхронного сервера в отдельном потоке
    server_thread = threading.Thread(target=run_uvicorn, daemon=True)
    server_thread.start()

    # 2. Ждем секунду, чтобы сервер успел поднять сокеты
    time.sleep(1.5)

    # 3. Запуск Qt GUI в главном потоке
    logger.info("Starting Desktop GUI...")
    qt_app = QApplication(sys.argv)
    qt_app.setApplicationName("MonitHome")
    
    bridge = PairingBridge()
    window = MainWindow()
    bridge.pairing_requested.connect(window.show_pairing_code)
    
    window.show()
    
    sys.exit(qt_app.exec())

if __name__ == "__main__":
    main()
