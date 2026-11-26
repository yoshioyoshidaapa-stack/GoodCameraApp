# ENボタンのあとなど、HuskyLens2 が固まったときに、I2C の2本線の状態を調べる
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_line_check.py
# 基板に hl_status.py が入っている必要がある。
from machine import Pin, I2C
import time
import hl_status


def hx(b):
    return " ".join("%02x" % x for x in b)


sda = Pin(21, Pin.IN, Pin.PULL_UP)
scl = Pin(22, Pin.IN, Pin.PULL_UP)
print("1) 線の状態 (1=高い=正常 / 0=低い=相手が引っ張っている):")
for i in range(5):
    print("   SDA=%d  SCL=%d" % (sda.value(), scl.value()))
    time.sleep_ms(100)

n = hl_status.bus_clear(Pin, 21, 22, time.sleep_us)
print("2) バス復旧: SCL を %d 回動かした (0 = 最初から SDA は高かった)" % n)

sda = Pin(21, Pin.IN, Pin.PULL_UP)
scl = Pin(22, Pin.IN, Pin.PULL_UP)
print("3) 復旧のあと: SDA=%d  SCL=%d" % (sda.value(), scl.value()))

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
try:
    d = i2c.readfrom(0x50, 16)
    print("4) 0x50 から読めた:", hx(d))
except OSError as e:
    print("4) 0x50 が応答しない:", e)
print("おわり")
