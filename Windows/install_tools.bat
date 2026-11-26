@echo off
chcp 65001 >nul
echo.
echo === 基板に、ファイルを送る道具(mpremote)を入れます ===
echo.
py --version
if errorlevel 1 (
  echo.
  echo Python が見つかりません。先に Python を入れてください。
  echo START_HERE.txt の「1. Python を入れる」を見てください。
  pause
  exit /b 1
)
py -m pip install --user esptool mpremote
if errorlevel 1 (
  echo.
  echo 道具を入れるのに失敗しました。インターネットにつながっているか、確認してください。
  pause
  exit /b 1
)
echo.
echo 道具を入れました。次は find_port.bat を、ダブルクリックしてください。
pause
