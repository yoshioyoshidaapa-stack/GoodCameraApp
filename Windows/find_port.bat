@echo off
chcp 65001 >nul
echo.
echo === 基板の COM 番号を調べます ===
echo 基板(ESP32)を、USB でつないだ状態で、実行してください。
echo.
echo --- 方法1: mpremote が見つけた機器 ---
py -m mpremote devs
echo.
echo --- 方法2: Windows が、見つけたシリアルポート ---
powershell -NoProfile -Command "Get-CimInstance Win32_SerialPort | Select-Object DeviceID,Name | Format-Table -AutoSize"
echo.
echo 「CH340」と書かれた行の、COM3 などが、基板の番号です。
echo 何も出ない場合は、START_HERE.txt の「3. 基板が見つからないとき」を見てください。
pause
