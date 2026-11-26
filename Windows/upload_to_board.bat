@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo === 基板に、4つのファイルを送ります ===
echo.
set "PORT=%~1"
if "%PORT%"=="" set /p PORT=基板の COM 番号を入力してください 例 COM3 : 
if "%PORT%"=="" (
  echo COM 番号が入力されませんでした。find_port.bat で調べてください。
  pause
  exit /b 1
)
for %%F in (lcd.py huskylens2.py hl_status.py main.py) do (
  if not exist "%%F" (
    echo %%F が、このフォルダに見つかりません。zip を全部、展開してから、実行してください。
    pause
    exit /b 1
  )
)
for %%F in (lcd.py huskylens2.py hl_status.py main.py) do (
  echo %%F を送っています...
  py -m mpremote connect %PORT% cp "%%F" ":%%F"
  if errorlevel 1 (
    echo.
    echo %%F の送信に失敗しました。次を確認してください。
    echo - COM 番号は合っていますか。find_port.bat で、もう一度、調べてください。
    echo - 別のソフトが、基板を使っていませんか。Thonny、Arduino IDE、別の黒い画面など。
    echo - 基板を、USB から、抜き差ししてから、もう一度、試してください。
    pause
    exit /b 1
  )
)
echo.
echo 4つのファイルを、送りました。基板の EN ボタンを押してください。
pause
