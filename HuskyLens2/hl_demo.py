# HuskyLens2 の試験: I2C で見つかるか → つながるか → 顔認識の結果を画面に出す
# 配線 (確定後): 緑=SDA→GPIO21, 青=SCL→GPIO22, 黒=GND (赤の扱いは DFRobot の返事待ち)
# 使い方(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run hl_demo.py
from machine import I2C, Pin
import time
import lcd
import huskylens2 as hl

lcd.init()
lcd.clear()
lcd.text("HuskyLens2", 8, 4, lcd.CYAN, 3)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
found = i2c.scan()
print("I2C で見つかった住所:", [hex(a) for a in found])
lcd.text("scan:" + ",".join(hex(a) for a in found)[:24], 8, 40, lcd.WHITE, 1)

if 0x50 not in found:
    lcd.text("0x50 not found", 8, 60, lcd.RED, 2)
    print("0x50 が見つかりません。配線・電源・I2C の設定を確認してください")
else:
    cam = hl.HuskyLens2(i2c)
    if not cam.knock():
        lcd.text("knock failed", 8, 60, lcd.RED, 2)
        print("knock に失敗しました（ファームウェアが 1.4.2 以上か確認）")
    else:
        lcd.text("knock OK", 8, 60, lcd.GREEN, 2)
        ok = cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION)
        print("顔認識に切り替え:", ok)
        while True:
            rs = cam.get_results(hl.ALGO_FACE_RECOGNITION)
            if rs is None:
                msg = "no reply   "
                col = lcd.RED
            elif len(rs) == 0:
                msg = "faces: 0   "
                col = lcd.YELLOW
            else:
                r = rs[0]
                msg = "faces:%d x%d y%d" % (len(rs), r["x"], r["y"])
                msg = msg + "      "
                col = lcd.GREEN
            lcd.text(msg[:15], 8, 90, col, 2)
            print(msg.strip())
            time.sleep_ms(300)
