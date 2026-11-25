# HuskyLens2 の返事を、そのまま見る試験（原因調べ用）
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_debug.py
# 基板に huskylens2.py が入っている必要がある。
from machine import I2C, Pin
import time
import huskylens2 as hl


def hx(b):
    return " ".join("%02x" % x for x in b)


i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
print("1) I2C で見つかった住所:", [hex(a) for a in i2c.scan()])

pkt = hl.HuskyLens2.build_packet(hl.CMD_KNOCK, hl.ALGO_ANY, bytes([1]) + bytes(13))
print("2) knock で送る内容 (%d バイト):" % len(pkt))
print("   ", hx(pkt))
print("   正しければ、返事の先頭は '55 aa 1a' になる")

for attempt in range(3):
    print("3) 試行", attempt + 1)
    try:
        i2c.writeto(0x50, pkt)
    except OSError as e:
        print("   送信エラー:", e)
        continue
    time.sleep_ms(10)
    for i in range(12):
        try:
            d = i2c.readfrom(0x50, 16)
        except OSError as e:
            print("   受信エラー:", e)
            break
        print("   受信 %2d:" % i, hx(d))
        time.sleep_ms(20)

print("4) 部品を使った確認")
cam = hl.HuskyLens2(i2c, timeout_ms=1000, retry=2)
print("   knock:", cam.knock())
print("おわり")
