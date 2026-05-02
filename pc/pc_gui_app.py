import sys
import threading
import time
import logging
import ctypes
from PySide6.QtWidgets import QApplication, QMainWindow, QVBoxLayout, QWidget
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtCore import QUrl, Qt
from PySide6.QtGui import QIcon

# Импортируем компоненты нашего агента
from pc_agent import app, socketio, p_manager, initialize_plugins, check_admin_required, is_admin

# Настройка логирования
logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s [%(name)s] %(message)s')
logger = logging.getLogger("DESKTOP_APP")

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MonitHome Dashboard")
        self.resize(1280, 800)
        
        # Создаем WebView
        self.browser = QWebEngineView()
        
        # Настройка макета
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self.browser)
        
        container = QWidget()
        container.setLayout(layout)
        self.setCentralWidget(container)
        
        # Темная тема для окна (соответствует дизайну)
        self.setStyleSheet("background-color: #020617;")
        
        # Загружаем URL (локальный сервер Flask)
        self.browser.setUrl(QUrl("http://127.0.0.1:5000"))

def run_flask():
    logger.info("Starting Flask server in background thread...")
    # Инициализация плагинов перед запуском
    initialize_plugins(socketio, p_manager)
    socketio.run(app, host='0.0.0.0', port=5000, debug=False, use_reloader=False)

def hide_console():
    """Скрывает окно консоли, если оно есть"""
    try:
        kernel32 = ctypes.WinDLL('kernel32')
        user32 = ctypes.WinDLL('user32')
        hWnd = kernel32.GetConsoleWindow()
        if hWnd:
            user32.ShowWindow(hWnd, 0) # SW_HIDE = 0
    except Exception:
        pass

def main():
    # Скрываем консоль сразу при старте (если запущено не через pythonw или PyInstaller --noconsole)
    hide_console()
    
    # Проверка прав администратора (как в pc_agent.py)
    if check_admin_required() and not is_admin():
        logger.warning("One or more plugins require ADMIN privileges. Relaunching...")
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, " ".join(sys.argv), None, 1)
        sys.exit(0)

    # Запускаем Flask в отдельном потоке
    flask_thread = threading.Thread(target=run_flask, daemon=True)
    flask_thread.start()

    # Даем серверу немного времени на запуск
    time.sleep(1)

    # Запускаем Qt приложение
    qt_app = QApplication(sys.argv)
    qt_app.setApplicationName("MonitHome")
    
    window = MainWindow()
    window.show()
    
    sys.exit(qt_app.exec())

if __name__ == "__main__":
    main()
