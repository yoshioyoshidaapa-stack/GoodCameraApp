@echo off
echo.
echo === Installing tools (esptool, mpremote) ===
echo.
py --version
if errorlevel 1 goto nopy
py -m pip install --user esptool mpremote
if errorlevel 1 goto pipfail
echo.
echo DONE. Next: double-click find_port.bat
pause
exit /b 0
:nopy
echo.
echo Python not found. Install Python first. See START_HERE.txt section 1.
pause
exit /b 1
:pipfail
echo.
echo Install failed. Check your internet connection.
pause
exit /b 1
