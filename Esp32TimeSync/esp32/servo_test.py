# サーボの試験: 基板につないだサーボが、正しく回るか確かめる。HuskyLens2 は、つながなくてよい。
# 実行(Mac): python3 -m mpremote connect /dev/cu.usbserial-8340 run servo_test.py
# 止めたいとき: Control + C (止まります)
import time
import machine
import drive


def pwm(pin):
    p = machine.PWM(machine.Pin(pin), freq=50)
    p.duty_u16(0)
    return p


d = drive.Drive(pwm)
d.enabled = True
try:
    for cmd in ("stop", "forward", "stop", "left", "stop", "right", "stop"):
        print(cmd)
        d.command(cmd)
        time.sleep(2)
finally:
    d.stop()
    print("done")
