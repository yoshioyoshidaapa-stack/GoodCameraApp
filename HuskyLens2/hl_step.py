# どの命令で HuskyLens2 が固まるかを調べる: 命令を1つずつ、6秒おきに送る
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_step.py
# 基板に huskylens2.py が入っている必要がある。
# 実行中は、HuskyLens2 の画面(映像が真っ黒になる/タッチが効かなくなる)を見ていてください。
from machine import I2C, Pin
import time
import huskylens2 as hl


def hx(b):
    return " ".join("%02x" % x for x in b)


def step(no, title):
    print("")
    print("=" * 40)
    print("★ %d) いま送る: %s" % (no, title))
    print("   (送る前の時刻 %d 秒)" % (time.ticks_ms() // 1000))


def wait(sec=6):
    print("   → %d 秒待ちます。HuskyLens2 の画面を見てください" % sec)
    time.sleep(sec)


i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=1500, retry=1)

step(1, "0x50 に、読み取り(16バイト)だけ")
try:
    print("   結果:", hx(i2c.readfrom(0x50, 16)))
except OSError as e:
    print("   0x50 が応答しない:", e)
wait()

step(2, "knock (つながっているかの確認)")
print("   結果:", cam.knock())
wait()

step(3, "顔認識の結果を要求(切り替えは、していない)")
rs = cam.get_results(hl.ALGO_FACE_RECOGNITION)
print("   結果:", rs)
wait()

step(4, "顔認識に切り替え (switch_algorithm)")
print("   結果:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
wait()

step(5, "顔認識の結果を要求(3回、1秒おき)")
for i in range(3):
    print("   %d回目:" % (i + 1), cam.get_results(hl.ALGO_FACE_RECOGNITION))
    time.sleep(1)
wait()

step(6, "もう一度、顔認識に切り替え(すでに顔認識のとき)")
print("   結果:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
wait()

print("")
print("おわり。途中で、映像が真っ黒になったか、タッチが効かなくなったか、どの ★ のあとか、教えてください。")
