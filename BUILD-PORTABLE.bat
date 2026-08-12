@echo off
setlocal
chcp 65001 >nul

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-all-portable.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" echo Portable release build failed with exit code %EXIT_CODE%.
if not defined JENA_RIPPER_NO_PAUSE pause
exit /b %EXIT_CODE%
