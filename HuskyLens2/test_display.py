# パソコンの Python で動かす試験:  python3 test_display.py
# 基板の画面 (時計 + HuskyLens2 の状態) の作りを、ニセの基板・ニセの画面・ニセの HuskyLens2 で確かめる。
#  - 状態が NOT_FOUND -> FOUND -> READY と進むか、失敗したら戻るか
#  - 画面の文字が 240x135 からはみ出さないか、行が重ならないか
#  - HuskyLens2 がいなくても、部品がなくても、時計が動くか
# ※ 本物の基板・画面・HuskyLens2 での確認ではない。

import os
import runpy
import sys
import time
import types

HERE = os.path.dirname(os.path.abspath(__file__))
ESP32 = os.path.join(HERE, "..", "Esp32TimeSync", "esp32")
sys.path.insert(0, HERE)
sys.path.insert(0, ESP32)

import huskylens2 as hl  # noqa: E402
import hl_status  # noqa: E402
from test_huskylens2 import FakeI2C  # noqa: E402


class FakeBus(FakeI2C):
    """scan() を持つ、ニセの I2C。present=False なら、何もいない。"""

    def __init__(self, present=True, **kw):
        FakeI2C.__init__(self, **kw)
        self.present = present

    def scan(self):
        return [0x50] if self.present else []

    def writeto(self, addr, buf):
        if not self.present:
            raise OSError("ENODEV")
        FakeI2C.writeto(self, addr, buf)

    def readfrom(self, addr, n):
        if not self.present:
            raise OSError("ENODEV")
        return FakeI2C.readfrom(self, addr, n)


# ---------- 状態の進み方 ----------
def test_state_flow_ready():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1)
    assert s.state == hl_status.NOT_FOUND
    s.update(0)
    assert s.state == hl_status.FOUND
    s.update(0)
    assert s.state == hl_status.READY
    s.update(0)
    assert s.faces == 3 and s.first["x"] == 320, (s.faces, s.first)
    detail, color, extra = s.view()
    assert detail == "Taro" and color == "red", (detail, color)  # ID 1 は赤。一番大きい行は人の名前
    assert extra == "n=3 ids=1,2,3 x320 y240", extra  # 顔の数は小さい行


def test_not_found_and_rescan():
    bus = FakeBus(present=False)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, scan_ms=3000)
    s.update(0)
    assert s.state == hl_status.NOT_FOUND
    assert s.view() == ("not found", "red", "check wiring / power")
    # 3秒たつまで、探し直さない
    bus.present = True
    s.update(1000)
    assert s.state == hl_status.NOT_FOUND
    s.update(3000)
    assert s.state == hl_status.FOUND


def test_lost_connection_goes_back():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, retry_ms=3000)
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY
    bus.present = False  # 線が抜けた
    s.update(1000)
    assert s.state == hl_status.NOT_FOUND and s.faces == 0 and s.first is None


def test_no_driver():
    s = hl_status.HuskyStatus(None)
    assert s.state == hl_status.NO_DRIVER
    s.update(0)
    assert s.view()[0] == "no driver"


# ---------- 画面の作り ----------
class FakeLcd(types.ModuleType):
    RED, YELLOW, GREEN, CYAN, WHITE, BLACK, BLUE = 0xF800, 0xFFE0, 0x07E0, 0x07FF, 0xFFFF, 0, 0x001F

    def __init__(self):
        types.ModuleType.__init__(self, "lcd")
        self.texts = []
        self.rects = []

    def init(self):
        pass

    def clear(self, color=0):
        pass

    def rect(self, x, y, w, h, color):
        self.rects.append((x, y, w, h))

    def text(self, s, x, y, color=0xFFFF, scale=2, bg=0):
        self.texts.append((s, x, y, color, scale))


class _Stop(Exception):
    pass


def run_main(bus, loops, with_hl_status=True, synced=False, boot_pattern=None):
    """main.py を、ニセの部品で、loops 回だけ動かして、描いた内容を返す。"""
    fake_lcd = FakeLcd()

    class FakeBLE:
        def active(self, v):
            pass

        def irq(self, f):
            self.f = f

        def gatts_register_services(self, svcs):
            return [(42,)]

        def gap_advertise(self, *a, **k):
            pass

        def gatts_read(self, h):
            return b"2026-11-25 09:30:00"

    ble_mod = types.ModuleType("bluetooth")
    ble_mod.UUID = lambda s: s
    ble_mod.BLE = FakeBLE
    created = {}

    class FakeRTC:
        def datetime(self, v=None):
            pass

    mach = types.ModuleType("machine")
    mach.RTC = FakeRTC
    class FakePin:
        IN = 0
        PULL_UP = 2

        def __init__(self, n, *a, **k):
            self.n = n

        def value(self):
            # BOOT ボタン: 1 = 押していない / 0 = 押している。1ループごとに1つ使う
            if boot_pattern:
                return boot_pattern.pop(0)
            return 1

    mach.Pin = FakePin
    mach.I2C = lambda *a, **k: bus

    saved_mods = {k: sys.modules.get(k) for k in ("bluetooth", "machine", "lcd", "hl_status")}
    sys.modules.update({"bluetooth": ble_mod, "machine": mach, "lcd": fake_lcd})
    if not with_hl_status:
        sys.modules["hl_status"] = None  # import すると ImportError になる

    state = {"n": 0, "off": 0}
    real = {k: getattr(time, k, None) for k in ("sleep_ms", "ticks_ms", "ticks_diff")}
    start = time.monotonic()
    time.ticks_ms = lambda: int((time.monotonic() - start) * 1000) + state["off"]
    time.ticks_diff = lambda a, b: a - b

    def fake_sleep(ms):
        state["n"] += 1
        state["off"] += ms
        if state["n"] >= loops:
            raise _Stop()

    time.sleep_ms = fake_sleep
    try:
        try:
            g = runpy.run_path(os.path.join(ESP32, "main.py"), run_name="__main__")
        except _Stop:
            pass
    finally:
        for k, v in real.items():
            if v is None:
                delattr(time, k)
            else:
                setattr(time, k, v)
        for k, v in saved_mods.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v
    return fake_lcd


def check_layout(fake_lcd):
    ranges = {}
    for s, x, y, col, scale in fake_lcd.texts:
        w = len(s) * 8 * scale
        h = 8 * scale
        assert x >= 0 and y >= 0, (s, x, y)
        assert x + w <= 240, "横にはみ出し: %r x=%d w=%d" % (s, x, w)
        assert y + h <= 135, "縦にはみ出し: %r y=%d h=%d" % (s, y, h)
        ranges[y] = (y, y + h)
    ys = sorted(ranges.values())
    for (a0, a1), (b0, b1) in zip(ys, ys[1:]):
        assert a1 <= b0, "行が重なっている: %r と %r" % ((a0, a1), (b0, b1))
    for x, y, w, h in fake_lcd.rects:
        assert x + w <= 240 and y + h <= 135, ("線がはみ出し", x, y, w, h)


def last_text(fake_lcd, y):
    found = [t for t in fake_lcd.texts if t[2] == y]
    return found[-1][0].rstrip() if found else None


def test_layout_husky_absent():
    fake = run_main(FakeBus(present=False), loops=40)
    check_layout(fake)
    assert last_text(fake, 4) == "--:--:--"
    assert last_text(fake, 34) == "NO SYNC"
    assert last_text(fake, 76) == "not found"
    assert last_text(fake, 118) == "BLE: waiting... ENR ON 0", last_text(fake, 118)


def test_layout_husky_ready():
    fake = run_main(FakeBus(present=True), loops=60)
    check_layout(fake)
    assert last_text(fake, 76) == "Taro", last_text(fake, 76)
    assert last_text(fake, 96) == "n=3 ids=1,2,3 x320 y240", last_text(fake, 96)
    assert last_text(fake, 107) == "PHOTO ON 1 IMG_0001.jpg", last_text(fake, 107)


def test_clock_runs_without_hl_status():
    fake = run_main(FakeBus(present=False), loops=10, with_hl_status=False)
    check_layout(fake)
    assert last_text(fake, 76) == "no driver"
    assert last_text(fake, 4) == "--:--:--"


def test_every_row_has_fixed_width():
    """前の文字が残らないよう、同じ行は、いつも同じ長さで描いている。"""
    fake = run_main(FakeBus(present=True), loops=60)
    widths = {}
    for s, x, y, col, scale in fake.texts:
        widths.setdefault(y, set()).add(len(s))
    for y, ws in widths.items():
        assert len(ws) == 1, "y=%d の行の文字数がばらばら: %r" % (y, ws)


def test_unexpected_error_does_not_stop():
    """HuskyLens2 の返事が想定外で IndexError などが起きても、止まらずに、エラーを表示する。"""
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1)
    s.update(0)
    assert s.state == hl_status.FOUND

    def boom():
        raise IndexError("short frame")

    s.cam.knock = boom
    s.update(0)  # ここで例外が外に出ないこと
    assert s.state == hl_status.NOT_FOUND
    assert s.err == "knock:IndexError", s.err
    detail, color, extra = s.view()
    assert detail == "not found" and extra == "knock:IndexError", (detail, extra)
    # 直ったら、再接続でき、エラー表示は消える
    s.cam.knock = lambda: True
    s.cam.switch_algorithm = lambda a: True
    s.update(3000)
    assert s.state == hl_status.FOUND
    s.update(3000)
    assert s.state == hl_status.READY and s.err == ""


def test_knock_returning_false_shows_reason():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1)
    s.update(0)
    s.cam.knock = lambda: False
    s.update(0)
    assert s.state == hl_status.NOT_FOUND and s.err == "knock failed", s.err

# ---------- 人ごとの色 ----------
def test_id_colors():
    assert [hl_status.id_color(i) for i in range(9)] == [
        "white", "red", "green", "blue", "yellow", "magenta", "cyan", "orange", "white"]


def test_main_person_is_the_biggest_face():
    rs = [{"id": 2, "w": 20, "h": 30}, {"id": 5, "w": 100, "h": 80}, {"id": 1, "w": 50, "h": 50}]
    assert hl_status.main_result(rs)["id"] == 5
    assert hl_status.main_result([]) is None


def test_person_color_bar():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, auto_photo=False)
    assert s.person_color() == "gray"  # つながる前
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY
    assert s.person_color() == "red"  # いちばん大きい顔は ID 1
    bus.faces_present = False
    s.update(1000)
    assert s.faces == 0 and s.person_color() == "gray"


# ---------- 写真 ----------
def test_take_photo_returns_file_name():
    bus = FakeBus(present=True)
    cam = hl.HuskyLens2(bus, timeout_ms=500)
    assert cam.take_photo(hl.RESOLUTION_640x480) == "IMG_0001.jpg"
    assert cam.take_photo() == "IMG_0002.jpg"
    bus.photo_fail = True
    assert cam.take_photo() == ""


def test_auto_photo_cooldown_and_cap():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=10000, photo_max=2)
    for t in (0, 0, 0):
        s.update(t)  # つながって、最初の結果で、1枚目
    assert bus.photo_requests == 1 and s.photos == 1 and s.last_photo == "IMG_0001.jpg"
    s.update(1000)
    s.update(5000)
    assert bus.photo_requests == 1, "間隔(10秒)が、たっていないので、撮らない"
    s.update(10000)
    assert bus.photo_requests == 2 and s.photos == 2
    s.update(20000)
    assert bus.photo_requests == 2, "上限(2枚)に達したので、撮らない"
    assert s.photo_line() == "PHOTO ON 2 IMG_0002.jpg"


def test_no_photo_without_face_or_when_off():
    bus = FakeBus(present=True)
    bus.faces_present = False
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=0)
    for t in (0, 0, 0, 1000, 2000):
        s.update(t)
    assert bus.photo_requests == 0, "顔がいないときは撮らない"
    bus.faces_present = True
    assert s.toggle_photo() is False
    s.update(3000)
    assert bus.photo_requests == 0, "OFF のときは撮らない"
    assert s.photo_line().startswith("PHOTO OFF")
    assert s.toggle_photo() is True
    s.update(4000)
    assert bus.photo_requests == 1


def test_photo_failure_keeps_connection():
    bus = FakeBus(present=True)
    bus.photo_fail = True
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=10000)
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY, "写真に失敗しても、接続は保つ"
    assert s.photo_fail is True and s.photos == 0
    assert s.photo_line() == "PHOTO ON 0 FAIL"
    # 失敗しても、すぐには撮り直さない
    s.update(1000)
    assert bus.photo_requests == 1


# ---------- 「準備中」と「返事の遅れ」への強さ ----------
def test_waits_for_settle_before_first_poll():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=2000, auto_photo=False)
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY
    assert s.view() == ("starting", "yellow", "waiting for HuskyLens2")
    s.update(1000)
    assert bus.result_requests == 0, "準備待ちの間は、結果を取りにいかない"
    s.update(2000)
    assert bus.result_requests == 1 and s.polled
    assert s.view()[0] == "Taro"


def test_a_few_missed_replies_do_not_drop_connection():
    bus = FakeBus(present=True)
    bus.drop_results = 2  # 最初の2回は、返事が来ない
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=5, poll_ms=1000, auto_photo=False)
    for t in (0, 0, 0):
        s.update(t)  # READY になり、1回目の結果の要求(返事なし)
    assert s.state == hl_status.READY and s.miss == 1
    assert s.view() == ("no reply", "yellow", "miss 1/5")
    assert s.person_color() == "gray"
    s.update(1000)
    assert s.state == hl_status.READY and s.miss == 2
    s.update(2000)  # 3回目で返事が来る
    assert s.state == hl_status.READY and s.miss == 0 and s.faces == 3
    assert s.view()[0] == "Taro"


def test_many_missed_replies_reconnect():
    bus = FakeBus(present=True)
    bus.drop_results = 99
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=3, poll_ms=1000, auto_photo=False)
    for t in (0, 0, 0, 1000, 2000):
        s.update(t)
    assert s.state == hl_status.NOT_FOUND and s.err == "result failed", (s.state, s.err)
    assert s.miss == 0


# ---------- 人の呼び名 (一番大きい行) ----------
def _r(id_, name="", w=10, h=10):
    return {"id": id_, "name": name, "w": w, "h": h, "x": 1, "y": 2}


def test_person_label_rules():
    assert hl_status.person_label(_r(0)) == "unknown", "ID 0 = まだ覚えていない顔"
    assert hl_status.person_label(_r(0, "Taro")) == "unknown"
    assert hl_status.person_label(_r(1, "Taro")) == "Taro"
    assert hl_status.person_label(_r(1, "Face")) == "ID01", "名前の初期値 Face は、使わない"
    assert hl_status.person_label(_r(2, "")) == "ID02"
    assert hl_status.person_label(_r(3, "\u592a\u90ce")) == "ID03", "日本語の名前は画面の文字で出せないので、ID にする"
    assert hl_status.person_label(_r(4, "A" * 30)) == "A" * 15


def test_no_face_and_unknown_face_view():
    bus = FakeBus(present=True)
    bus.faces_present = False
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, auto_photo=False)
    for t in (0, 0, 0):
        s.update(t)
    assert s.view() == ("no face", "white", "n=0")
    assert s.person_color() == "gray"
    # 覚えていない顔(ID 0)だけが見えているとき
    s.state, s.polled = hl_status.READY, True
    s.faces, s.ids, s.first = 1, [0], _r(0, "", 100, 80)
    detail, color, extra = s.view()
    assert (detail, color) == ("unknown", "white"), (detail, color)
    assert extra == "n=1 ids=0 x1 y2", extra


# ---------- 自動で覚えさせる (ID01, ID02 …) ----------
def test_learn_returns_new_id():
    bus = FakeBus(present=True)
    cam = hl.HuskyLens2(bus, timeout_ms=500)
    assert cam.learn(hl.ALGO_FACE_RECOGNITION) == 1
    assert cam.learn(hl.ALGO_FACE_RECOGNITION) == 2
    assert cam.learn_block(hl.ALGO_FACE_RECOGNITION, 320, 240, 100, 90) == 3
    assert bus.last_block == (320, 240, 100, 90)
    bus.learn_fail = True
    assert cam.learn(hl.ALGO_FACE_RECOGNITION) == 0
    assert cam.forget(hl.ALGO_FACE_RECOGNITION) is True and bus.forgot


def test_auto_enroll_after_stable_seconds():
    bus = FakeBus(present=True)
    bus.unknown_mode = True
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, auto_photo=False,
                              enroll_stable=3, enroll_cooldown_ms=10000)
    for t in (0, 0, 0):
        s.update(t)  # 接続 + 1回目の結果(安定 1/3)
    assert bus.learn_requests == 0 and s.view()[0] == "unknown"
    s.update(1000)  # 安定 2/3
    assert bus.learn_requests == 0
    s.update(2000)  # 安定 3/3 -> 覚えさせる
    assert bus.learn_requests == 1 and s.enrolled == 1 and s.new_id == 1
    assert s.enroll_line() == "ENR ON 1"
    s.update(3000)  # 覚えた顔は ID 1。5秒の間は「NEW」と出る
    assert s.view()[:2] == ("NEW ID01", "red"), s.view()
    s.update(8000)  # 5秒たつと、普通の表示(ID01)に戻る
    assert s.view()[:2] == ("ID01", "red"), s.view()
    assert bus.learn_requests == 1, "覚えた顔(ID 1)は、もう覚えさせない"


def _stub_status(**kw):
    class Cam:
        def __init__(self):
            self.n = 0

        def learn(self, algo, timeout_ms=None):
            self.n += 1
            return self.n

    s = hl_status.HuskyStatus(FakeBus(present=True), settle_ms=0, max_miss=1, auto_photo=False,
                              enroll_stable=1, enroll_cooldown_ms=10000, **kw)
    s.cam = Cam()
    s.state, s.polled = hl_status.READY, True
    return s


def _face(s, id_=0, x=320, y=240, w=100, h=100, n=1):
    s.faces, s.first, s.ids = n, _r(id_, "", w, h), [id_]
    s.first["x"], s.first["y"] = x, y


def test_enroll_conditions():
    # 条件がそろえば覚えさせる(基準)
    s = _stub_status()
    _face(s)
    s._maybe_enroll(0)
    assert s.cam.n == 1
    # 条件が1つでも欠けると、覚えさせない
    for label, setup in (
        ("顔が2つ", lambda s: _face(s, n=2)),
        ("すでに覚えた顔(ID 1)", lambda s: _face(s, id_=1)),
        ("顔が小さい", lambda s: _face(s, w=60, h=60)),
        ("十字が枠の外(横)", lambda s: _face(s, x=500)),
        ("十字が枠の外(縦)", lambda s: _face(s, y=400)),
    ):
        s = _stub_status()
        setup(s)
        s._maybe_enroll(0)
        assert s.cam.n == 0, label
    s = _stub_status()
    _face(s)
    s.toggle_enroll()
    s._maybe_enroll(0)
    assert s.cam.n == 0 and s.enroll_line() == "ENR OFF 0", "OFF のときは覚えさせない"


def test_enroll_cooldown_and_cap():
    s = _stub_status(enroll_max=2)
    _face(s)
    s._maybe_enroll(0)
    assert s.cam.n == 1
    s._maybe_enroll(5000)
    assert s.cam.n == 1, "10秒たつまでは、次を覚えさせない"
    s._maybe_enroll(10000)
    assert s.cam.n == 2
    s._maybe_enroll(30000)
    assert s.cam.n == 2, "上限(2人)に達したので、覚えさせない"


def test_enroll_needs_stable_face():
    s = _stub_status()
    s.enroll_stable = 3
    _face(s)
    s._maybe_enroll(0)
    s._maybe_enroll(1000)
    assert s.cam.n == 0
    _face(s, n=2)  # 途中で、条件がくずれたら、数え直し
    s._maybe_enroll(2000)
    _face(s)
    s._maybe_enroll(3000)
    s._maybe_enroll(4000)
    assert s.cam.n == 0, "また最初から3回数える"
    s._maybe_enroll(5000)
    assert s.cam.n == 1


def test_enroll_failure_is_shown_and_waits():
    bus = FakeBus(present=True)
    bus.unknown_mode = True
    bus.learn_fail = True
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, poll_ms=1000, auto_photo=False,
                              enroll_stable=1, enroll_cooldown_ms=10000)
    for t in (0, 0, 0):
        s.update(t)
    assert bus.learn_requests == 1 and s.enrolled == 0 and s.enroll_line() == "ENR FAIL"
    assert s.state == hl_status.READY, "覚えさせるのに失敗しても、接続は保つ"
    s.update(1000)
    assert bus.learn_requests == 1, "失敗しても、すぐには、やり直さない"


# ---------- BOOT ボタン (短押し = 写真 / 長押し = 自動登録) ----------
def test_boot_short_press_toggles_photo():
    pattern = [1, 1, 0, 0, 0, 1]  # 3ループ(0.3秒)押す
    fake = run_main(FakeBus(present=True), loops=60, boot_pattern=pattern)
    assert last_text(fake, 107).startswith("PHOTO OFF"), last_text(fake, 107)
    assert last_text(fake, 118) == "BLE: waiting... ENR ON 0", "短押しでは、自動登録は変わらない"


def test_boot_long_press_toggles_enroll():
    pattern = [1, 1] + [0] * 20 + [1]  # 20ループ(2秒)押す
    fake = run_main(FakeBus(present=True), loops=60, boot_pattern=pattern)
    assert last_text(fake, 118) == "BLE: waiting... ENR OFF 0", last_text(fake, 118)
    assert last_text(fake, 107).startswith("PHOTO ON"), "長押しでは、写真は変わらない"


def test_no_micropython_incompatible_calls():
    """パソコンの Python にはあるが、MicroPython には無い書き方を、基板に入れるファイルで使っていないか。"""
    import re
    files = [
        os.path.join(HERE, "huskylens2.py"),
        os.path.join(ESP32, "hl_status.py"),
        os.path.join(ESP32, "main.py"),
        os.path.join(ESP32, "lcd.py"),
    ]
    bad = [
        (r"\.pop\(", "bytearray/str には pop() が無い (list なら可)"),
        (r"\.hex\(", "bytes.hex() は古い MicroPython に無い"),
        (r"\.format\(", ".format() は使わず % を使う"),
        (r"(?<![\w<>=!@%&])f[\"\']", "f文字列は使わない"),
        (r"\.removeprefix\(|\.removesuffix\(", "MicroPython に無い"),
        (r"\bfrom typing\b|\bimport typing\b", "typing は無い"),
        (r"\bwalrus\b|:=", "代入式は使わない"),
    ]
    problems = []
    for f in files:
        for n, line in enumerate(open(f, encoding="utf-8").read().split("\n"), 1):
            code = line.split("#")[0]
            for pat, why in bad:
                if re.search(pat, code):
                    problems.append("%s:%d %s -> %s" % (os.path.basename(f), n, line.strip(), why))
    assert not problems, "\n".join(problems)


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("OK ", t.__name__)
    print("すべて成功 (%d 件)" % len(tests))
