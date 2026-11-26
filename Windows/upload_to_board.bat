@echo off
cd /d "%~dp0"
echo.
echo === Sending 4 files to the board ===
echo.
set "PORT=%~1"
if "%PORT%"=="" set /p PORT=Enter COM port, for example COM3 : 
if "%PORT%"=="" goto noport
for %%F in (lcd.py huskylens2.py hl_status.py main.py) do call :check %%F
for %%F in (lcd.py huskylens2.py hl_status.py main.py) do call :send %%F
if errorlevel 1 exit /b 1
echo.
echo DONE. Press the EN button on the board.
pause
exit /b 0

:check
if exist "%~1" exit /b 0
echo %~1 not found in this folder. Extract the whole zip first.
pause
exit /b 1

:send
echo Sending %~1 ...
py -m mpremote connect %PORT% cp "%~1" ":%~1"
if errorlevel 1 goto fail
exit /b 0

:fail
echo.
echo FAILED to send %~1.
echo - Is the COM number right? Run find_port.bat again.
echo - Close Thonny, Arduino IDE, other black windows using the board.
echo - Unplug and replug the board USB, then try again.
pause
exit /b 1

:noport
echo No COM port entered. Run find_port.bat to find it.
pause
exit /b 1
