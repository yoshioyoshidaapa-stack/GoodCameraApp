@echo off
cd /d "%~dp0"
echo.
echo === Run a diagnostic script on the board ===
echo.
set "PORT=%~1"
set "SCRIPT=%~2"
if "%PORT%"=="" set /p PORT=Enter COM port, for example COM3 : 
if "%SCRIPT%"=="" set /p SCRIPT=Enter script, for example tools\hl_object_debug.py : 
if not exist "%SCRIPT%" goto nofile
echo Press Ctrl+C to stop.
py -m mpremote connect %PORT% run "%SCRIPT%"
echo.
pause
exit /b 0
:nofile
echo %SCRIPT% not found.
pause
exit /b 1
