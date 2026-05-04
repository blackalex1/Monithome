import subprocess
import os
import sys
import shutil

def build_single_exe():
    # 1. Настройки
    project_root = os.path.dirname(os.path.abspath(__file__))
    entry_script = os.path.join(project_root, "pc_gui_app.py")
    output_name = "MonitHome"
    bin_folder = os.path.join(project_root, "bin")
    
    print("--- Preparing build environment (SINGLE EXE MODE) ---")
    
    # Очистка
    for folder in [bin_folder, "build_tmp", f"{output_name}.spec"]:
        path = os.path.join(project_root, folder)
        if os.path.exists(path):
            if os.path.isdir(path): shutil.rmtree(path)
            else: os.remove(path)

    os.makedirs(bin_folder, exist_ok=True)

    # 2. Сборка (режим --onefile)
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconsole",
        "--onefile",             # Упаковать всё в один файл
        f"--name={output_name}",
        f"--distpath={bin_folder}",
        f"--workpath=build_tmp",
        "--clean",
        f"--add-data=web;web",
        f"--add-data=plugins;plugins",
        f"--icon=web/favicon.png",
        entry_script
    ]

    print(f"--- Running PyInstaller (this may take a while...) ---")
    try:
        subprocess.run(cmd, check=True)
        
        # Очистка временных файлов
        if os.path.exists("build_tmp"): shutil.rmtree("build_tmp")
        if os.path.exists(f"{output_name}.spec"): os.remove(f"{output_name}.spec")

        print("\n" + "="*50)
        print(f"BUILD SUCCESSFUL!")
        print(f"Your single executable is here: {os.path.join(bin_folder, output_name + '.exe')}")
        print("="*50)
            
    except Exception as e:
        print(f"\nBUILD FAILED: {e}")

if __name__ == "__main__":
    build_single_exe()
