@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-apk-windows.ps1"
pause
