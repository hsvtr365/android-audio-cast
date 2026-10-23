@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  where py >nul 2>&1
  if not errorlevel 1 (
    py -3 -m venv .venv
  ) else (
    python -m venv .venv
  )
  if errorlevel 1 goto :error
)
".venv\Scripts\python.exe" -c "import sys; assert sys.version_info >= (3,11), 'Python 3.11+ required'"
if errorlevel 1 goto :error
".venv\Scripts\python.exe" -c "import aiohttp, cryptography, zeroconf" >nul 2>&1
if errorlevel 1 (
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 goto :error
)
".venv\Scripts\python.exe" -m cast_inspector --open-browser %*
if errorlevel 1 goto :error
goto :eof
:error
echo Failed. Check Python 3.11+, network interface and port availability.
pause
exit /b 1
