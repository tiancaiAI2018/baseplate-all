@echo off
chcp 65001 >nul
setlocal
rem Run frontend in the foreground: Ctrl+C or closing this window stops it.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\dev-server.ps1" -Target frontend
set "CODE=%errorlevel%"
rem 0 = normal exit, interrupt codes = user stopped the service.
if not "%CODE%"=="0" if not "%CODE%"=="-1073741510" if not "%CODE%"=="3221225786" pause
exit /b %CODE%
