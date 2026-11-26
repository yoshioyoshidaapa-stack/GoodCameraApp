@echo off
echo.
echo === Find the COM port of the board ===
echo Connect the ESP32 board by USB first.
echo.
echo --- mpremote devices ---
py -m mpremote devs
echo.
echo --- Windows serial ports ---
powershell -NoProfile -Command "Get-CimInstance Win32_SerialPort | Select-Object DeviceID,Name | Format-Table -AutoSize"
echo.
echo The COM number on the CH340 line (for example COM3) is your board.
echo If nothing is shown, see START_HERE.txt section 3.
pause
