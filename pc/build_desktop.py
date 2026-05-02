import os
import subprocess
import sys

def build():
    print("🚀 Starting MonitHome Desktop Build...")
    
    # 1. Проверяем наличие билда GUI
    gui_dist = os.path.abspath("../pc_gui/dist")
    if not os.path.exists(gui_dist):
        print(f"❌ Error: GUI dist folder not found at {gui_dist}")
        print("💡 Please run 'npm run build' in pc_gui directory first.")
        return

    # 2. Формируем команду для PyInstaller
    # Мы используем --noconsole для чистого GUI приложения
    # --onefile для одного EXE
    # --add-data для включения файлов фронтенда
    cmd = [
        "venv\\Scripts\\pyinstaller.exe",
        "--noconsole",
        "--onefile",
        "--name", "MonitHome",
        "--add-data", f"{gui_dist};dist",
        # Для QtWebEngine нужно иногда явно указывать сборку всех зависимостей
        "--collect-all", "PySide6",
        # Если есть иконка, можно добавить --icon=icon.ico
        "pc_gui_app.py"
    ]
    
    print(f"📦 Packaging into EXE (this may take a few minutes)...")
    try:
        subprocess.run(cmd, check=True)
        print("✅ Build complete! You can find the executable in the 'dist' folder.")
    except subprocess.CalledProcessError as e:
        print(f"❌ Build failed with error: {e}")

if __name__ == "__main__":
    build()
