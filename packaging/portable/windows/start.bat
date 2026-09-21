@echo off
setlocal
set "APP_HOME=%~dp0"
set "DATA_HOME=%LOCALAPPDATA%\JenaRipper"
set "PROFILE_HOME=%APPDATA%\jena-ripper"

if not exist "%DATA_HOME%\logs" mkdir "%DATA_HOME%\logs"
if not exist "%PROFILE_HOME%" mkdir "%PROFILE_HOME%"

"%APP_HOME%runtime\bin\java.exe" ^
  -Dfile.encoding=UTF-8 ^
  -Dspring.profiles.active=desktop ^
  "-Djena-ripper.settings.path=%PROFILE_HOME%\profiles.json" ^
  "-Dlogging.file.name=%DATA_HOME%\logs\jena-ripper.log" ^
  -jar "%APP_HOME%jena-ripper.jar" %*

set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
