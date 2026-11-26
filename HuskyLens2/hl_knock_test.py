# 試験C: 接続確認(knock)のあと、8秒待ってから、顔認識に切り替えると、映像が出るか調べる
# 準備: HuskyLens2 の画面で、先に「顔認識」を選んで、カメラの映像が出ている状態にしておく。
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_knock_test.py
# 基板に huskylens2.py が入っている必要がある。
from machine import I2C, Pin
import time
import huskylens2 as hl

WAIT_SEC = 8  # knock のあと、切り替えるまで待つ秒数

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=3000, retry=1)

print("★ 1) いま knock を送ります。HuskyLens2 の画面が、メニューに戻るか、見てください")
print("   結果:", cam.knock())
print("   → %d 秒待ちます" % WAIT_SEC)
time.sleep(WAIT_SEC)

print("★ 2) いま顔認識に切り替えます")
print("   結果:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
print("   → 10 秒待ちます。カメラの映像が出るか、真っ黒か、タッチが効くか、見てください")
time.sleep(10)
print("おわり。1) でメニューに戻ったか、2) のあとに映像が出たか、教えてください。")
