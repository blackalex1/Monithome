import sys
import threading
import time
import logging
import ctypes
import uvicorn

try:
    from PySide6.QtWidgets import QApplication, QMainWindow, QVBoxLayout, QWidget
    from PySide6.QtWebEngineWidgets import QWebEngineView
    from PySide6.QtCore import QUrl
except ImportError:
    print("PySide6 is not installed. Please run: pip install PySide6")
    sys.exit(1)

# Импортируем ASGI-приложение (FastAPI + SocketIO) из main.py
from main import socket_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] GUI: %(message)s")
logger = logging.getLogger("GUI")

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
        self.browser.setUrl(QUrl("http://127.0.0.1:5000"))

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
    
    window = MainWindow()
    window.show()
    
    sys.exit(qt_app.exec())

if __name__ == "__main__":
    main()
