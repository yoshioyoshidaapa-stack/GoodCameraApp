# 物体認識の結果を、そのまま見る(名前に "bird" と入るか、位置が出るかの確認用)
# 準備: 鳥の写真(スマホの画面でよい)を、HuskyLens2 のカメラに映す。
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_object_debug.py
# 基板に huskylens2.py が入っている必要がある。止めるときは Ctrl+C。
from machine import I2C, Pin
import time
import huskylens2 as hl

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
cam = hl.HuskyLens2(i2c, timeout_ms=3000, retry=1)
print("knock:", cam.knock(timeout_ms=1500))
time.sleep(4)
print("物体認識(80種類)に切り替え:", cam.switch_algorithm(hl.ALGO_OBJECT_RECOGNITION, timeout_ms=3000))
time.sleep(3)
print("1秒おきに、結果を表示します。名前(name)に bird と入るか、x y w h(位置と大きさ)が出るか、見てください。")
n = 0
while True:
    n += 1
    rs = cam.get_results(hl.ALGO_OBJECT_RECOGNITION)
    if rs is None:
        print("%3d: 返事なし" % n)
    elif not rs:
        print("%3d: 何も見えていない" % n)
    else:
        for r in rs:
            print("%3d: name=%r id=%d x=%d y=%d w=%d h=%d conf=%.2f content=%r" % (
                n, r["name"], r["id"], r["x"], r["y"], r["w"], r["h"], r["conf"], r["content"]))
    time.sleep(1)
