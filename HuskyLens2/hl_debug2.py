# 「結果を読む」ときの、HuskyLens2 の返事を、そのまま見る調査用プログラム
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_debug2.py
# 基板に huskylens2.py が入っている必要がある。HuskyLens2 は、つないだままにする。
from machine import I2C, Pin
import time
import huskylens2 as hl


def hx(b):
    return " ".join("%02x" % x for x in b)


i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=2000, retry=2)
print("1) knock:", cam.knock())
print("2) 顔認識に切り替え:", cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION))
print("3) 準備を3秒待つ")
time.sleep(3)

for n in range(5):
    print("4) 結果を要求 %d 回目" % (n + 1))
    pkt = hl.HuskyLens2.build_packet(hl.CMD_GET_RESULT, hl.ALGO_FACE_RECOGNITION, b"")
    t0 = time.ticks_ms()
    cam._write(pkt)
    deadline = time.ticks_ms() + 2000
    got = 0
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        f = cam._read_frame(deadline)
        if f is None:
            break
        got += 1
        print("   %d ミリ秒後: 種類=0x%02x algo=%d 長さ=%d 中身=%s" % (
            time.ticks_diff(time.ticks_ms(), t0), f[2], f[3], f[4], hx(f[5:-1])))
        deadline = time.ticks_ms() + 400  # 続きの返事を、少しだけ待つ
    if got == 0:
        print("   2秒待っても、返事なし")
    time.sleep_ms(1000)
print("おわり")
