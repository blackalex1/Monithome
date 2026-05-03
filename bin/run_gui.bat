@echo off
cd /d "%~dp0.."
call venv\Scripts\activate
python pc_v2\pc_gui_app.py
pause
