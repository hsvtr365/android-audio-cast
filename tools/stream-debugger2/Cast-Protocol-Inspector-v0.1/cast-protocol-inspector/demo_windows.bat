@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Run start_windows.bat first.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" scripts\demo_sender.py --host 127.0.0.1 --port 8009
pause
