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


def test_track_when_bird_visible():
    pl = drive.Planner()
    assert pl.update(None, 0, "stop") == "stop"
    assert pl.update(FakeHusky(ready=False), 0, "forward") == "stop", "つながっていないときは止まる"
    assert pl.update(FakeHusky(x=320), 0, "stop") == "forward"
    assert pl.update(FakeHusky(x=100), 0, "stop") == "left"
    assert pl.update(FakeHusky(x=600), 0, "stop") == "right"
    assert pl.update(FakeHusky(x=600, w=400), 0, "stop") == "stop", "近いので止まる"
    assert pl.update(FakeHusky(holding=True, x=100), 0, "right") == "right", "検出が途切れた間は、今の動きを続ける"
    assert not pl.scouting


def test_wait_then_scout_after_losing_bird():
    pl = drive.Planner(lost_ms=3000, turn_ms=500, look_ms=1000, steps=3, forward_ms=2000)
    pl.update(FakeHusky(x=600), 0, "stop")  # 右寄りに見えていた
    none = FakeHusky(faces=0)
    assert pl.update(none, 100, "stop") == "stop", "見失った直後は、止まって待つ"
    assert pl.update(none, 2900, "stop") == "stop"
    assert not pl.scouting
    assert pl.update(none, 3000, "stop") == "right", "待ったあと、最後に見えた側(右)へ回り始める"
    assert pl.scouting
    assert pl.update(none, 3400, "right") == "right"
    assert pl.update(none, 3500, "right") == "stop", "回ったら、止まって見る"
    assert pl.update(none, 4400, "stop") == "stop"
    assert pl.update(none, 4500, "stop") == "right", "見終わったら、また回る"
    t = 4500
    out = []
    for _ in range(80):  # 回る→見る を繰り返す: 3回目の見終わりで前進
        t += 100
        out.append(pl.update(none, t, "x"))
    assert "forward" in out, "1周しても見つからなければ、前進する"
    k = out.index("forward")
    assert "left" not in out, "回る向きは、変わらない"
    assert out[k:k + 19].count("forward") >= 18, "前進は、決めた時間(2秒)続く"


def test_bird_found_while_scouting_returns_to_tracking():
    pl = drive.Planner(lost_ms=1000, turn_ms=500, look_ms=1000, steps=3, forward_ms=2000)
    pl.update(FakeHusky(x=100), 0, "stop")
    none = FakeHusky(faces=0)
    assert pl.update(none, 1000, "stop") == "left", "最後に左寄りで見えたので、左へ回る"
    assert pl.scouting
    assert pl.update(FakeHusky(x=330), 1100, "left") == "forward", "見つけたら、すぐ追いかける"
    assert not pl.scouting
    assert pl.update(none, 1200, "forward") == "stop", "また見失ったら、待つところから"


def test_scouting_stops_when_not_connected():
    pl = drive.Planner(lost_ms=1000)
    none = FakeHusky(faces=0)
    pl.update(none, 0, "stop")
    assert pl.update(none, 1000, "stop") == "right"
    assert pl.update(FakeHusky(ready=False), 1100, "right") == "stop"
    assert not pl.scouting
    assert pl.update(none, 1200, "stop") == "stop", "つながり直したら、また待ってから探す"


def test_wheel_signs_back():
    assert drive.wheel_signs("back") == (-1, -1)


def test_sonar_measure():
    class Pin:
        def __init__(self):
            self.log = []

        def value(self, v=None):
            self.log.append(v)

    sleeps = []
    pin = Pin()
    s = drive.Sonar(pin, object(), lambda echo, level, timeout: 580, lambda us: sleeps.append(us))
    assert abs(s.measure() - 10.0) < 1e-6, "580 マイクロ秒 = 10 cm"
    assert pin.log == [0, 1, 0] and sleeps == [2, 10]
    s2 = drive.Sonar(Pin(), object(), lambda *a: -2, lambda us: None)
    assert s2.measure() is None, "返事がなければ None"
    s3 = drive.Sonar(Pin(), object(), lambda *a: None, lambda us: None)
    assert s3.measure() is None


def _scout_to_forward(pl, none, start=1000):
    """見失ってから、偵察の「前進」に入るところまで時間を進める。(時刻, 最後の動き) を返す。"""
    t = start
    cmd = "stop"
    for _ in range(400):
        cmd = pl.update(none, t, cmd)
        if cmd == "forward":
            return t, cmd
        t += 100
    raise AssertionError("前進に入らない")


def test_avoid_wall_while_scouting_forward():
    pl = drive.Planner(lost_ms=1000, turn_ms=500, look_ms=500, steps=2, forward_ms=5000,
                       stop_cm=25, confirm_n=2, back_ms=1000, avoid_turn_ms=1000)
    pl.update(FakeHusky(x=100), 0, "stop")  # 左寄りで見えていた -> side = -1(左)
    none = FakeHusky(faces=0)
    t, _ = _scout_to_forward(pl, none)
    assert pl.update(none, t + 100, "forward", 100) == "forward", "遠いうちは、前進を続ける"
    assert pl.update(none, t + 200, "forward", 20) == "forward", "1回だけ近くても、まだ前進(ノイズ対策)"
    assert pl.update(none, t + 300, "forward", 20) == "back", "続けて近い: 下がる"
    assert pl.avoiding
    assert pl.update(none, t + 800, "back", 20) == "back"
    assert pl.update(none, t + 1300, "back", 40) == "right", "下がったあと、壁から離れる向き(さっきと逆の右)へ回る"
    assert pl.update(none, t + 2000, "right", 40) == "right"
    assert pl.update(none, t + 2400, "right", 40) == "stop", "回り終わったら、止まって見る(偵察を再開)"
    assert not pl.avoiding


def test_no_sensor_means_no_avoidance():
    pl = drive.Planner(lost_ms=1000, turn_ms=500, look_ms=500, steps=2, forward_ms=1000)
    pl.update(FakeHusky(x=100), 0, "stop")
    none = FakeHusky(faces=0)
    t, _ = _scout_to_forward(pl, none)
    for k in range(1, 5):
        assert pl.update(none, t + k * 100, "forward", None) == "forward", "距離センサーがなければ、壁よけはしない"


def test_tracking_forward_stops_when_wall_ahead():
    pl = drive.Planner(stop_cm=25, confirm_n=2)
    bird = FakeHusky(x=320)
    assert pl.update(bird, 0, "stop", 100) == "forward"
    assert pl.update(bird, 100, "forward", 20) == "forward"
    assert pl.update(bird, 200, "forward", 20) == "stop", "鳥は前だが、手前が近い: 止まる"
    assert pl.update(FakeHusky(x=100), 300, "stop", 20) == "left", "回るのは、壁が近くてもできる"
    assert pl.update(bird, 400, "stop", 100) == "forward", "壁が遠のけば、また前進"


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("OK ", t.__name__)
    print("すべて成功 (%d 件)" % len(tests))
