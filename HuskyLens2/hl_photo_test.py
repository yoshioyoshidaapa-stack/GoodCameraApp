# 試験A: 「写真を撮る命令」で HuskyLens2 が固まるか調べる
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_photo_test.py
# 基板に huskylens2.py が入っている必要がある。
# 実行中は、HuskyLens2 の画面(映像が真っ黒になる/タッチが効かなくなる)を見ていてください。
from machine import I2C, Pin
import time
import huskylens2 as hl

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=3000, retry=1)

print("1) knock:", cam.knock())
print("2) 顔認識に切り替え:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
print("   → 8 秒待ちます。映像が普通に出ているか、見てください")
time.sleep(8)

for n in (1, 2, 3):
    print("")
    print("★ %d) いま写真を撮る命令を送ります (640x480)" % (n + 2))
    name = cam.take_photo(hl.RESOLUTION_640x480, timeout_ms=5000)
    print("   結果(ファイル名。空なら失敗):", repr(name))
    print("   → 10 秒待ちます。映像が真っ黒になっていないか、タッチが効くか、見てください")
    time.sleep(10)

print("")
print("おわり。映像が真っ黒になったのは、どの ★ のあとか、教えてください。")
