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
    s = hl_status.HuskyStatus(bus)
    assert s.state == hl_status.NOT_FOUND
    s.update(0)
    assert s.state == hl_status.FOUND
    s.update(0)
    assert s.state == hl_status.READY
    s.update(0)
    assert s.faces == 3 and s.first["x"] == 320, (s.faces, s.first)
    detail, color, extra = s.view()
    assert detail == "faces:3" and color == "green" and extra == "x320 y240 100x80", (detail, extra)


def test_not_found_and_rescan():
    bus = FakeBus(present=False)
    s = hl_status.HuskyStatus(bus, scan_ms=3000)
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
    s = hl_status.HuskyStatus(bus, poll_ms=1000, retry_ms=3000)
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
    RED, YELLOW, GREEN, CYAN, WHITE, BLACK = 0xF800, 0xFFE0, 0x07E0, 0x07FF, 0xFFFF, 0

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


def run_main(bus, loops, with_hl_status=True, synced=False):
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
    mach.Pin = lambda n, *a, **k: n
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
    assert last_text(fake, 118) == "BLE: waiting..."


def test_layout_husky_ready():
    fake = run_main(FakeBus(present=True), loops=60)
    check_layout(fake)
    assert last_text(fake, 76) == "faces:3", last_text(fake, 76)
    assert last_text(fake, 96) == "x320 y240 100x80", last_text(fake, 96)


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
    s = hl_status.HuskyStatus(bus)
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
    s = hl_status.HuskyStatus(bus)
    s.update(0)
    s.cam.knock = lambda: False
    s.update(0)
    assert s.state == hl_status.NOT_FOUND and s.err == "knock failed", s.err


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("OK ", t.__name__)
    print("すべて成功 (%d 件)" % len(tests))
