@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo === 調査用のプログラムを実行します ===
echo.
set "PORT=%~1"
set "SCRIPT=%~2"
if "%PORT%"=="" set /p PORT=基板の COM 番号を入力してください 例 COM3 : 
if "%SCRIPT%"=="" set /p SCRIPT=実行するファイル名を入力してください 例 tools\hl_object_debug.py : 
if not exist "%SCRIPT%" (
  echo %SCRIPT% が見つかりません。
  pause
  exit /b 1
)
echo 止めるときは Ctrl キーを押しながら C を押します。
py -m mpremote connect %PORT% run "%SCRIPT%"
echo.
pause
