@echo off
chcp 65001 >nul
setlocal
rem 编译后端：clean package，保证 jar 与当前源码一致。
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-backend.ps1" %*
set "CODE=%errorlevel%"
if not "%CODE%"=="0" pause
exit /b %CODE%
