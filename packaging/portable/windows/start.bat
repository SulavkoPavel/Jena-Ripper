@echo off
setlocal
set "APP_HOME=%~dp0"
set "DATA_HOME=%LOCALAPPDATA%\JenaRipper"

if not exist "%DATA_HOME%\logs" mkdir "%DATA_HOME%\logs"

"%APP_HOME%runtime\bin\java.exe" ^
  -Dfile.encoding=UTF-8 ^
  -Dspring.profiles.active=desktop ^
  "-Djena-ripper.settings.path=%DATA_HOME%\jena-ripper-settings.json" ^
  "-Dlogging.file.name=%DATA_HOME%\logs\jena-ripper.log" ^
  -jar "%APP_HOME%jena-ripper.jar" %*

set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
