@echo off
cd /d "%~dp0"
set "PORT=%~1"
if "%PORT%"=="" set /p PORT=Enter COM port, for example COM3 : 
py check_board.py %PORT%
pause
