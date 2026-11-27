# パソコンの Python で動かす試験:  python3 test_drive.py
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "Esp32TimeSync", "esp32"))
import drive


class FakePwm:
    def __init__(self, pin):
        self.pin = pin
        self.duty = None

    def duty_u16(self, v):
        self.duty = v


class FakeHusky:
    def __init__(self, ready=True, holding=False, faces=1, x=320, w=50):
        self.ready = ready
        self.holding = holding
        self.faces = faces
        self.first = {"x": x, "w": w} if faces else None

    def is_ready(self):
        return self.ready


def test_decide():
    assert drive.decide(320, 50) == "forward"
    assert drive.decide(220, 50) == "forward", "中央の幅の端は、まだ中央"
    assert drive.decide(420, 50) == "forward"
    assert drive.decide(219, 50) == "left", "左寄り"
    assert drive.decide(421, 50) == "right", "右寄り"
    assert drive.decide(0, 50) == "left"
    assert drive.decide(640, 50) == "right"
    assert drive.decide(320, 320) == "stop", "近づいたら止まる"
    assert drive.decide(None) == "stop"


def test_wheel_signs_turn_in_place():
    assert drive.wheel_signs("forward") == (1, 1)
    assert drive.wheel_signs("left") == (-1, 1)
    assert drive.wheel_signs("right") == (1, -1)
    assert drive.wheel_signs("stop") == (0, 0)


def test_pulse_us():
    assert drive.pulse_us(0, False) == 0, "止めるときは、信号を出さない"
    assert drive.pulse_us(1, False, speed=100, stop=1500) == 1600
    assert drive.pulse_us(-1, False, speed=100, stop=1500) == 1400
    assert drive.pulse_us(1, True, speed=100, stop=1500) == 1400, "反転"


def test_drive_commands_and_safety():
    made = []

    def factory(pin):
        p = FakePwm(pin)
        made.append(p)
        return p

    d = drive.Drive(factory, pin_left=25, pin_right=26, invert_left=False, invert_right=True, speed=100, stop=1500)
    L, R = made
    assert (L.pin, R.pin) == (25, 26)
    assert L.duty == 0 and R.duty == 0, "最初は止まっている"
    d.command("forward")
    assert L.duty == 0 and R.duty == 0, "入り切りが「切」なら、動かない"
    assert d.toggle() is True
    d.command("forward")
    assert L.duty == drive.Drive._duty(1600), L.duty
    assert R.duty == drive.Drive._duty(1400), "右は反転: 前進でも信号は逆"
    d.command("left")
    assert L.duty == drive.Drive._duty(1400) and R.duty == drive.Drive._duty(1400)
    d.command("right")
    assert L.duty == drive.Drive._duty(1600) and R.duty == drive.Drive._duty(1600)
    d.command("forward")
    assert d.toggle() is False
    assert L.duty == 0 and R.duty == 0, "切にしたら、すぐ止まる"
    d.command("forward")
    assert L.duty == 0 and R.duty == 0


def test_plan():
    assert drive.plan(None, "forward") == "stop"
    assert drive.plan(FakeHusky(ready=False), "forward") == "stop", "つながっていないときは止まる"
    assert drive.plan(FakeHusky(faces=0), "forward") == "stop", "鳥がいないときは止まる"
    assert drive.plan(FakeHusky(x=320), "stop") == "forward"
    assert drive.plan(FakeHusky(x=100), "stop") == "left"
    assert drive.plan(FakeHusky(x=600), "stop") == "right"
    assert drive.plan(FakeHusky(x=600, w=400), "stop") == "stop", "近いので止まる"
    assert drive.plan(FakeHusky(holding=True, x=100), "right") == "right", "検出が途切れた間は、今の動きを続ける"


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("OK ", t.__name__)
    print("すべて成功 (%d 件)" % len(tests))
