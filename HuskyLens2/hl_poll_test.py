# 試験B: ESP32 が「通信の途中で」止まると、HuskyLens2 が固まるか調べる
# 使い方は、手順の説明を見てください(基板の main.py を、先に消しておく)。
# このプログラムは、HuskyLens2 に、1秒おきに、顔認識の結果を要求し続けます(止めるときは Ctrl+C)。
from machine import I2C, Pin
import time
import huskylens2 as hl

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=1500, retry=1)
print("knock:", cam.knock())
print("顔認識に切り替え:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
print("これから、1秒おきに、結果を要求し続けます。")
print("10秒ほどたったら、基板の EN ボタンを押してください。")
n = 0
while True:
    n += 1
    rs = cam.get_results(hl.ALGO_FACE_RECOGNITION)
    print("%3d 回目:" % n, "返事なし" if rs is None else "顔 %d 個" % len(rs))
    time.sleep(1)
