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
        self.setWindowTitle("MonitHome Dashboard v2")
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

def run_uvicorn():
    logger.info("Starting Async FastAPI Server in background thread...")
    # Запускаем uvicorn без автоперезагрузки в фоновом потоке
    uvicorn.run(socket_app, host="0.0.0.0", port=5000, log_level="warning")

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
    
    # В будущем здесь можно добавить проверку прав администратора (ctypes.windll.shell32.IsUserAnAdmin)
    
    # 1. Запуск асинхронного сервера в отдельном потоке
    server_thread = threading.Thread(target=run_uvicorn, daemon=True)
    server_thread.start()

    # 2. Ждем секунду, чтобы сервер успел поднять сокеты
    time.sleep(1.5)

    # 3. Запуск Qt GUI в главном потоке
    logger.info("Starting Desktop GUI...")
    qt_app = QApplication(sys.argv)
    qt_app.setApplicationName("MonitHome v2")
    
    bridge = PairingBridge()
    window = MainWindow()
    bridge.pairing_requested.connect(window.show_pairing_code)
    
    window.show()
    
    sys.exit(qt_app.exec())

if __name__ == "__main__":
    main()
