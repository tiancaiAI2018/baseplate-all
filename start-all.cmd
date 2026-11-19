@echo off
chcp 65001 >nul
rem Start backend and frontend in two separate console windows.
rem Each service runs in the foreground: Ctrl+C or closing the window stops it.
rem You can also run start-backend.cmd / start-frontend.cmd separately.
cd /d "%~dp0"
start "BASEPLATE Backend" cmd /k start-backend.cmd
start "BASEPLATE Frontend" cmd /k start-frontend.cmd
exit /b 0
