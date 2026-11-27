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
        if len(buf) > 2 and buf[2] == hl.CMD_ACTION_TAKE_PHOTO and getattr(self, "fake_lcd", None) is not None:
            # 写真の要求が来た時点で、画面に「SHOOT」が、すでに描かれていたか
            self.shoot_before_photo = any(t[0].strip() == "SHOOT" for t in self.fake_lcd.texts)
        FakeI2C.writeto(self, addr, buf)

    def readfrom(self, addr, n):
        if not self.present:
            raise OSError("ENODEV")
        return FakeI2C.readfrom(self, addr, n)


# ---------- 状態の進み方 ----------
def test_state_flow_ready():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, scan_ms=3000)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, retry_ms=3000)
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
        self.events = []  # 描いた順の記録 (見本の画像を作るために使う)

    def init(self):
        pass

    def clear(self, color=0):
        pass

    def rect(self, x, y, w, h, color):
        self.rects.append((x, y, w, h))
        self.events.append(("rect", x, y, w, h, color))

    def text(self, s, x, y, color=0xFFFF, scale=2, bg=0):
        self.texts.append((s, x, y, color, scale))
        self.events.append(("text", s, x, y, color, scale))


class _Stop(Exception):
    pass


def run_main(bus, loops, with_hl_status=True, synced=False, boot_pattern=None, frame_ends=None, mode="face"):
    """main.py を、ニセの部品で、loops 回だけ動かして、描いた内容を返す。"""
    import tempfile
    workdir = tempfile.mkdtemp()  # mode.txt を、試験ごとに、独立させる
    old_cwd = os.getcwd()
    os.chdir(workdir)
    if mode:
        with open("mode.txt", "w") as f:
            f.write(mode)
    fake_lcd = FakeLcd()
    bus.fake_lcd = fake_lcd

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
        OPEN_DRAIN = 7

        def __init__(self, n, *a, **k):
            self.n = n

        def value(self, v=None):
            if v is not None:
                return None  # 出力の設定は、試験では何もしない
            # BOOT ボタン(GPIO0): 1 = 押していない / 0 = 押している。1ループごとに1つ使う
            if self.n == 0 and boot_pattern:
                return boot_pattern.pop(0)
            return 1

    mach.Pin = FakePin
    mach.I2C = lambda *a, **k: bus

    saved_mods = {k: sys.modules.get(k) for k in ("bluetooth", "machine", "lcd", "hl_status")}
    sys.modules.update({"bluetooth": ble_mod, "machine": mach, "lcd": fake_lcd})
    if not with_hl_status:
        sys.modules["hl_status"] = None  # import すると ImportError になる

    state = {"n": 0, "off": 0}
    real = {k: getattr(time, k, None) for k in ("sleep_ms", "sleep_us", "ticks_ms", "ticks_diff")}
    time.sleep_us = lambda us: None
    start = time.monotonic()
    time.ticks_ms = lambda: int((time.monotonic() - start) * 1000) + state["off"]
    time.ticks_diff = lambda a, b: a - b

    def fake_sleep(ms):
        if frame_ends is not None:
            frame_ends.append(len(fake_lcd.events))  # 1ループ(=1画面)が描き終わった位置
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
        fake_lcd.mode_file = open("mode.txt").read() if os.path.exists("mode.txt") else None
        os.chdir(old_cwd)
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


def _overlaps(boxes):
    keys = sorted(boxes)
    for i, a in enumerate(keys):
        for b in keys[i + 1:]:
            ax0, ay0, ax1, ay1 = a[0], a[1], a[0] + boxes[a][0], a[1] + boxes[a][1]
            bx0, by0, bx1, by1 = b[0], b[1], b[0] + boxes[b][0], b[1] + boxes[b][1]
            if ax0 < bx1 and bx0 < ax1 and ay0 < by1 and by0 < ay1:
                return a, b
    return None


def check_layout(fake_lcd):
    """文字が画面からはみ出さないか。同じ画面(=全部を消してから、次に全部を消すまで)の中で、文字の場所が重ならないか。"""
    epochs = [{}]
    for ev in fake_lcd.events:
        if ev[0] == "rect" and ev[1:5] == (0, 0, 240, 135):  # 画面を全部消した = 画面の切り替え
            epochs.append({})
        elif ev[0] == "text":
            _, s, x, y, col, scale = ev
            w = len(s) * 8 * scale
            h = 8 * scale
            assert x >= 0 and y >= 0, (s, x, y)
            assert x + w <= 240, "横にはみ出し: %r x=%d w=%d" % (s, x, w)
            assert y + h <= 135, "縦にはみ出し: %r y=%d h=%d" % (s, y, h)
            old = epochs[-1].get((x, y), (0, 0))
            epochs[-1][(x, y)] = (max(old[0], w), max(old[1], h))
    for boxes in epochs:
        hit = _overlaps(boxes)
        assert hit is None, "文字の場所が重なっている: %r と %r" % hit
    for x, y, w, h in fake_lcd.rects:
        assert x + w <= 240 and y + h <= 135, ("線がはみ出し", x, y, w, h)


def last_text_at(fake_lcd, x, y):
    found = [t for t in fake_lcd.texts if t[1] == x and t[2] == y]
    return found[-1][0].rstrip() if found else None


def last_text(fake_lcd, y):
    found = [t for t in fake_lcd.texts if t[2] == y]
    return found[-1][0].rstrip() if found else None


def test_layout_husky_absent():
    fake = run_main(FakeBus(present=False), loops=40)
    check_layout(fake)
    assert last_text(fake, 2) == "not found"
    assert last_text(fake, 52) == "", "顔がないときは、顔の数の行は、空"
    assert last_text(fake, 72) == "PHOTO ON 0"
    assert last_text(fake, 92) == "ENR ON 0"
    assert last_text(fake, 110) == "check wiring / power"
    assert last_text(fake, 122) == "BLE: waiting..."
    assert not any(t[2] == 4 for t in fake.texts), "時計(時刻)は、もう出さない"

def test_layout_husky_ready():
    bus = FakeBus(present=True)
    fake = run_main(bus, loops=140)
    check_layout(fake)
    labels = [t for t in fake.texts if t[2] == 2]
    assert labels[-1][0].rstrip() == "Taro" and labels[-1][4] == 4, labels[-1]  # 短い名前は、4倍の大きい文字
    assert last_text(fake, 52) == "n=3 ids=1,2,3", last_text(fake, 52)
    assert last_text(fake, 72) == "PHOTO ON 1", last_text(fake, 72)
    assert last_text(fake, 110) == "n=3 ids=1,2,3 x320 y240", last_text(fake, 110)
    # 写真を撮ったとき: 「SHOOT」が出て、その時点は、写真の要求より前だった
    assert any(t[0].strip() == "SHOOT" and t[4] == 4 for t in labels), "SHOOT が出ていない"
    assert bus.shoot_before_photo is True, "写真を頼む前に、SHOOT を描いていない"
    assert labels[-1][0].strip() != "SHOOT", "撮影のあとは、人の表示に戻る"

def test_runs_without_hl_status():
    fake = run_main(FakeBus(present=False), loops=10, with_hl_status=False)
    check_layout(fake)
    assert last_text(fake, 2) == "no driver"
    assert last_text(fake, 110) == "hl_status.py missing"

def test_every_row_has_fixed_width():
    """前の文字が残らないよう、同じ行・同じ倍率の文字は、いつも同じ長さで描いている。"""
    fake = run_main(FakeBus(present=True), loops=140)
    widths = {}
    for s, x, y, col, scale in fake.texts:
        widths.setdefault((y, scale), set()).add(len(s))
    for key, ws in widths.items():
        assert len(ws) == 1, "y=%d 倍率%d の行の文字数がばらばら: %r" % (key[0], key[1], ws)

def test_unexpected_error_does_not_stop():
    """HuskyLens2 の返事が想定外で IndexError などが起きても、止まらずに、エラーを表示する。"""
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1)
    s.update(0)
    assert s.state == hl_status.FOUND

    def boom(timeout_ms=None):
        raise IndexError("short frame")

    s.cam.knock = boom
    s.update(0)  # ここで例外が外に出ないこと
    assert s.state == hl_status.NOT_FOUND
    assert s.err == "knock:IndexError", s.err
    detail, color, extra = s.view()
    assert detail == "not found" and extra == "knock:IndexError", (detail, extra)
    # 直ったら、再接続でき、エラー表示は消える
    s.cam.knock = lambda timeout_ms=None: True
    s.cam.switch_algorithm = lambda a, timeout_ms=None: True
    s.update(3000)
    assert s.state == hl_status.FOUND
    s.update(3000)
    assert s.state == hl_status.READY and s.err == ""


def test_knock_returning_false_shows_reason():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1)
    s.update(0)
    s.cam.knock = lambda timeout_ms=None: False
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=10000, photo_max=2)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=0)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, photo_cooldown_ms=10000)
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
    s = hl_status.HuskyStatus(bus, settle_ms=2000, knock_settle_ms=0, auto_photo=False)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=5, poll_ms=1000, auto_photo=False)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=3, poll_ms=1000, auto_photo=False)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False)
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, auto_photo=False,
                              enroll_stable=3, enroll_cooldown_ms=10000)
    for t in (0, 0, 0):
        s.update(t)  # 接続 + 1回目の結果(安定 1/3)
    assert bus.learn_requests == 0 and s.view()[0] == "unknown"
    s.update(1000)  # 安定 2/3
    assert bus.learn_requests == 0
    s.update(2000)  # 安定 3/3 -> 覚えさせる
    assert bus.learn_requests == 1 and s.enrolled == 1 and s.new_id == 1
    assert s.enroll_line() == "ENR ON 1"
    assert s.person_color() == "red", "NEW ID の間も、色の帯は、新しい ID(赤)にそろえる"
    s.update(3000)  # 覚えた顔は ID 1。5秒の間は「NEW」と出る
    assert s.view()[:2] == ("NEW ID01", "red"), s.view()
    assert s.person_color() == "red"
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

    s = hl_status.HuskyStatus(FakeBus(present=True), settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False,
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
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, auto_photo=False,
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
    assert last_text(fake, 72).startswith("PHOTO OFF"), last_text(fake, 72)
    assert last_text(fake, 92) == "ENR ON 0", "短押しでは、自動登録は変わらない"


def test_boot_long_press_toggles_enroll():
    pattern = [1, 1] + [0] * 20 + [1]  # 20ループ(2秒)押す
    fake = run_main(FakeBus(present=True), loops=60, boot_pattern=pattern)
    assert last_text(fake, 92) == "ENR OFF 0", last_text(fake, 92)
    assert last_text(fake, 72).startswith("PHOTO ON"), "長押しでは、写真は変わらない"


# ---------- 「SHOOT」の仕組みと、大きい表示のための部品 ----------
def test_shoot_callback_runs_before_capture():
    bus = FakeBus(present=True)
    seen = {}
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, poll_ms=1000, auto_enroll=False)

    def cb():
        seen["requests_at_callback"] = bus.photo_requests  # 撮影を頼む前に呼ばれるはず
        seen["shoot_show"] = s.shoot_show

    s.shoot_cb = cb
    for t in (0, 0, 0):
        s.update(t)
    assert seen == {"requests_at_callback": 0, "shoot_show": True}, seen
    assert bus.photo_requests == 1 and s.shoot_show is True, "撮影の直後も、次に結果を読むまで、SHOOT を出す"
    s.update(1000)  # 次に結果を読むと、SHOOT は終わる
    assert s.shoot_show is False


def test_shoot_callback_error_does_not_break():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, auto_enroll=False)

    def bad():
        raise ValueError("draw failed")

    s.shoot_cb = bad
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY and s.photos == 1, "表示のエラーで、撮影や接続は、止まらない"


def test_summary_and_photo_short():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, auto_enroll=False)
    assert s.summary() == ""
    for t in (0, 0, 0):
        s.update(t)
    assert s.summary() == "n=3 ids=1,2,3"
    assert s.photo_short() == "PHOTO ON 1"
    s.toggle_photo()
    assert s.photo_short() == "PHOTO OFF 1"
    s.photo_fail = True
    assert s.photo_short() == "PHOTO OFF 1!"


# ---------- ESP32 の再起動(ENボタン)で固まった HuskyLens2 を放す: I2C のバス復旧 ----------
class _StuckSlave:
    """データ線(SDA)を、SCL が release_after 回動くまで、低いまま引っ張る、ニセの相手。"""

    def __init__(self, release_after):
        self.release_after = release_after
        self.pulses = 0
        self.log = []


def _pin_factory(slave):
    class P:
        OPEN_DRAIN = 7

        def __init__(self, n, mode=None, value=None):
            self.n = n
            self.v = 1 if value is None else value

        def value(self, v=None):
            if v is None:
                if self.n == 21:  # SDA
                    return 0 if slave.pulses < slave.release_after else 1
                return self.v
            if self.n == 22 and v == 1 and self.v == 0:
                slave.pulses += 1  # SCL が「低 -> 高」になるたびに、1パルス
            self.v = v
            slave.log.append((self.n, v))
            return None

    return P


def test_bus_clear_releases_stuck_slave():
    slave = _StuckSlave(release_after=3)
    n = hl_status.bus_clear(_pin_factory(slave), 21, 22, lambda us: None)
    assert n == 3, "相手が放すまで、SCL を3回動かす"
    assert slave.log[-3:] == [(21, 0), (22, 1), (21, 1)], "最後に STOP (SCL が高いまま、SDA を 低->高)"


def test_bus_clear_does_nothing_when_bus_is_free():
    slave = _StuckSlave(release_after=0)
    assert hl_status.bus_clear(_pin_factory(slave), 21, 22, lambda us: None) == 0


def test_bus_clear_gives_up_after_nine_pulses():
    slave = _StuckSlave(release_after=999)
    assert hl_status.bus_clear(_pin_factory(slave), 21, 22, lambda us: None) == 9


def test_probe_uses_only_address_0x50():
    """見つけるとき、全住所の scan() ではなく、0x50 だけに触れる。"""
    calls = []

    class Bus(FakeBus):
        def scan(self):
            calls.append("scan")
            return FakeBus.scan(self)

        def readfrom(self, addr, n):
            calls.append(("readfrom", addr, n))
            return FakeBus.readfrom(self, addr, n)

    s = hl_status.HuskyStatus(Bus(present=True), settle_ms=0, knock_settle_ms=0, max_miss=1)
    s.update(0)
    assert s.state == hl_status.FOUND
    assert "scan" not in calls and calls[0] == ("readfrom", 0x50, 16), calls


# ---------- 接続確認(knock)のあと、待ってから、顔認識に切り替える ----------
def test_waits_after_knock_before_switching():
    """ESP32 の再起動(EN)のあと、HuskyLens2 がメニューに戻る途中で、切り替えを送ると、映像が出なくなる。"""
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, max_miss=1, knock_settle_ms=4000, auto_photo=False, auto_enroll=False)
    calls = []
    real_knock, real_switch = s.cam.knock, s.cam.switch_algorithm
    s.cam.knock = lambda timeout_ms=None: calls.append("knock") or real_knock(timeout_ms=timeout_ms)
    s.cam.switch_algorithm = lambda a, timeout_ms=None: calls.append("switch") or real_switch(a, timeout_ms=timeout_ms)
    s.update(0)   # 0x50 が見つかる
    assert s.state == hl_status.FOUND
    s.update(0)   # knock
    assert s.state == hl_status.KNOCKED and calls == ["knock"]
    assert s.view() == ("connecting", "yellow", "waiting for HuskyLens2")
    s.update(1000)
    s.update(3999)
    assert calls == ["knock"], "4秒たつまでは、切り替えを送らない"
    s.update(4000)
    assert calls == ["knock", "switch"] and s.state == hl_status.READY


def test_slow_switch_reply_does_not_cause_reconnect_loop():
    """切り替えの返事が届かなくても、すぐには、つなぎ直さない。結果が読めれば、そのまま使う。"""
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=1000, max_miss=5, auto_photo=False, auto_enroll=False)
    s.update(0)
    s.update(0)
    seen = {}
    s.cam.switch_algorithm = lambda a, timeout_ms=None: seen.setdefault("timeout", timeout_ms) and False
    s.update(1000)
    assert seen["timeout"] == 3000, "切り替えの返事は、3秒まで待つ"
    assert s.state == hl_status.READY and s.err == "algo?", (s.state, s.err)
    assert s.view() == ("starting", "yellow", "algo?")
    s.update(1000)  # 結果が読める
    assert s.state == hl_status.READY and s.polled and s.err == "" and s.view()[0] == "Taro"


def test_unreadable_results_after_failed_switch_reconnect():
    bus = FakeBus(present=True)
    bus.drop_results = 99
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=2, poll_ms=1000, auto_photo=False, auto_enroll=False)
    s.update(0)
    s.update(0)
    s.cam.switch_algorithm = lambda a, timeout_ms=None: False
    s.state, s._next = hl_status.KNOCKED, 0
    s.update(0)  # 切り替えの返事なし -> READY
    assert s.state == hl_status.READY
    s.update(1000)
    s.update(2000)  # 結果が2回続けて読めない -> つなぎ直す
    assert s.state == hl_status.NOT_FOUND and s.err == "result failed", (s.state, s.err)


# ---------- 鳥の機能 (物体認識で bird を探し、画面に位置を出す) ----------
BIRDS = [("bird", 0, 200, 120, 60, 50, 0.8), ("person", 0, 320, 240, 200, 400, 0.9), ("bird", 0, 500, 300, 100, 80, 0.6)]


def _bird_status(objects, **kw):
    bus = FakeBus(present=True)
    bus.objects = objects
    args = dict(target="bird", settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False)
    args.update(kw)
    return bus, hl_status.HuskyStatus(bus, **args)


def test_mode_settings():
    assert hl_status.mode_settings("bird") == (hl.ALGO_OBJECT_RECOGNITION, "bird")
    assert hl_status.mode_settings("face") == (hl.ALGO_FACE_RECOGNITION, None)


def test_bird_filter_keeps_only_birds():
    bus, s = _bird_status(BIRDS)
    for t in (0, 0, 0):
        s.update(t)
    assert s.faces == 2, "鳥は2羽(人は数えない)"
    assert s.seen == "bird,person", s.seen
    assert s.boxes == [(200, 120, 60, 50, s.boxes[0][4]), (500, 300, 100, 80, s.boxes[1][4])]
    assert s.first["x"] == 500, "いちばん大きい鳥が、代表"
    assert s.is_ready()


def test_bird_filter_with_only_person():
    bus, s = _bird_status([BIRDS[1]])
    for t in (0, 0, 0):
        s.update(t)
    assert s.faces == 0 and s.boxes == [] and s.first is None and s.seen == "person"


def test_no_enroll_in_bird_mode():
    bus, s = _bird_status([("bird", 0, 320, 240, 200, 200, 0.9)], enroll_stable=1)
    for t in (0, 0, 0, 1000, 2000, 3000):
        s.update(t)
    assert bus.learn_requests == 0, "鳥のときは、覚えさせない"


def test_photo_only_when_bird_is_seen():
    bus, s = _bird_status([BIRDS[1]], auto_photo=True)
    for t in (0, 0, 0, 1000):
        s.update(t)
    assert bus.photo_requests == 0, "人だけのときは、撮らない"
    bus.objects = [BIRDS[0]]
    s.update(2000)
    assert bus.photo_requests == 1, "鳥が見えたら、撮る"


def test_set_algo_switches_without_reconnecting():
    bus = FakeBus(present=True)
    s = hl_status.HuskyStatus(bus, settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False, auto_enroll=False)
    for t in (0, 0, 0):
        s.update(t)
    assert s.state == hl_status.READY and bus.algo == hl.ALGO_FACE_RECOGNITION
    s.set_algo(hl.ALGO_OBJECT_RECOGNITION, "bird", 1000)
    assert s.state == hl_status.KNOCKED and s.faces == 0 and s.boxes == [] and s.target == "bird"
    s.update(1000)
    assert bus.algo == hl.ALGO_OBJECT_RECOGNITION and s.state == hl_status.READY and not s.polled


def _bird_run(objects, loops=140, **kw):
    bus = FakeBus(present=True)
    bus.objects = objects
    return bus, run_main(bus, loops=loops, mode="bird", **kw)


def _yellow_rects(fake):
    return [e for e in fake.events if e[0] == "rect" and e[5] == 0xFFE0]


def test_bird_screen_shows_count_and_position():
    bus, fake = _bird_run(BIRDS)
    check_layout(fake)
    assert last_text_at(fake, 164, 8) == "BIRD"
    assert last_text_at(fake, 164, 28) == "x2"
    assert last_text_at(fake, 164, 56) == "60%", "いちばん大きい鳥の自信"
    assert last_text_at(fake, 164, 78) == "x500 y300"
    assert last_text_at(fake, 164, 102) == "bird,pers"
    # 地図に、鳥の黄色い枠が、描かれている(例: 鳥 x500,y300 -> 地図の中心 (125, 83))
    near = [e for e in _yellow_rects(fake) if 100 <= e[1] <= 150 and 60 <= e[2] <= 100]
    assert near, "鳥の位置に、黄色い枠がない"
    for e in _yellow_rects(fake):
        _, x, y, w, h, _c = e
        assert 0 <= x and x + w <= 160 and 8 <= y and y + h <= 128, ("枠が、地図からはみ出し", e)


def test_bird_screen_with_no_bird():
    bus, fake = _bird_run([BIRDS[1]])
    check_layout(fake)
    assert last_text_at(fake, 164, 28) == "x0"
    assert last_text_at(fake, 164, 56) == "" and last_text_at(fake, 164, 78) == ""
    assert not _yellow_rects(fake), "鳥がいないときは、枠を描かない"


def test_bird_wait_screen_before_connection():
    fake = run_main(FakeBus(present=False), loops=30, mode="bird")
    check_layout(fake)
    assert last_text_at(fake, 0, 50) == "not found"
    assert last_text_at(fake, 0, 76) == "check wiring / power"


def test_bird_screen_shows_shoot_before_photo():
    bus = FakeBus(present=True)
    bus.objects = [BIRDS[0]]
    fake = run_main(bus, loops=140, mode="bird")
    shoot = [t for t in fake.texts if t[0] == "SHOOT"]
    assert shoot and shoot[0][1:3] == (20, 56) and shoot[0][4] == 3, "地図の上に SHOOT が出ていない"
    assert bus.shoot_before_photo is True, "写真を頼む前に、SHOOT を描いていない"


def test_default_mode_is_bird_when_no_file():
    bus = FakeBus(present=True)
    fake = run_main(bus, loops=140, mode=None)
    assert last_text_at(fake, 164, 8) == "BIRD"


def test_boot_very_long_press_switches_mode_and_saves_it():
    bus = FakeBus(present=True)
    bus.objects = [BIRDS[0]]
    pattern = [1] * 60 + [0] * 45 + [1]  # 準備ができたあと、4.5秒押し続ける
    fake = run_main(bus, loops=200, mode="bird", boot_pattern=pattern)
    assert fake.mode_file == "face", "モードを、基板(mode.txt)に残す"
    assert bus.algo == hl.ALGO_FACE_RECOGNITION, "顔認識に、切り替える命令を送る"
    labels = [t for t in fake.texts if t[2] == 2 and t[1] == 0]
    assert labels, "顔認識の画面が、描かれている"


def test_boot_between_1_5_and_4_seconds_does_not_switch_mode():
    bus = FakeBus(present=True)
    pattern = [1] * 60 + [0] * 25 + [1]  # 2.5秒
    fake = run_main(bus, loops=140, mode="face", boot_pattern=pattern)
    assert fake.mode_file == "face"


def test_bird_match_by_name_or_content_and_case():
    """実機の返事で、鳥の名前が、どの欄に、どんな書き方で入るか、まだ分からない。ゆるく調べる。"""
    mk = lambda name, content="": {"name": name, "content": content}
    assert hl_status.matches_target(mk("bird"), "bird")
    assert hl_status.matches_target(mk("Bird"), "bird"), "大文字でも"
    assert hl_status.matches_target(mk("bird:ID1"), "bird"), "前後に文字が付いても"
    assert hl_status.matches_target(mk("", "bird"), "bird"), "名前が空で、内容に入っていても"
    assert hl_status.matches_target(mk("Object", "BIRD"), "bird")
    assert not hl_status.matches_target(mk("person"), "bird")
    assert not hl_status.matches_target(mk("", ""), "bird")


def test_bird_found_when_label_is_in_content():
    bus = FakeBus(present=True)
    # 名前の欄が空で、内容の欄に bird と入っている返事(実機が、そうだった場合の想定)
    bus.objects = [("", 0, 300, 200, 80, 60, 0.7)]
    import test_huskylens2 as th
    orig = th.result_data

    def patched(id_, x, y, w, h, conf, name="", content=""):
        return orig(id_, x, y, w, h, conf, "", "bird")

    th.result_data = patched
    try:
        s = hl_status.HuskyStatus(bus, target="bird", settle_ms=0, knock_settle_ms=0, max_miss=1, auto_photo=False)
        for t in (0, 0, 0):
            s.update(t)
    finally:
        th.result_data = orig
    assert s.faces == 1 and s.seen == "bird", (s.faces, s.seen)


# ---------- 日本語の名前(実機で確認: HuskyLens2 の言語が日本語だと、名前が日本語で返る) ----------
BIRDS_JP = [("鳥", 0, 200, 120, 60, 50, 0.8), ("人", 0, 320, 240, 200, 400, 0.9), ("鳥", 0, 500, 300, 100, 80, 0.6)]


def test_ascii_name():
    assert hl_status.ascii_name("bird") == "bird"
    assert hl_status.ascii_name("鳥") == "bird"
    assert hl_status.ascii_name("バス") == "bus"
    assert hl_status.ascii_name("人") == "person"
    assert hl_status.ascii_name("未知の物") == "?", "知らない日本語は、画面に出せないので ?"
    assert hl_status.ascii_name("") == ""


def test_bird_japanese_name_is_found():
    bus, s = _bird_status(BIRDS_JP)
    for t in (0, 0, 0):
        s.update(t)
    assert s.faces == 2, "日本語の「鳥」も、鳥として数える"
    assert s.seen == "bird,person", s.seen
    assert hl_status.matches_target({"name": "鳥", "content": ""}, "bird")
    assert not hl_status.matches_target({"name": "人", "content": ""}, "bird")


# ---------- 検出が途切れても、前の枠を、少しの間、残す ----------
def test_boxes_are_held_for_a_few_polls():
    bus, s = _bird_status([BIRDS_JP[0]], hold_polls=2)
    for t in (0, 0, 0):
        s.update(t)
    assert s.faces == 1 and not s.holding
    bus.objects = []  # 鳥が見えなくなった
    s.update(1000)
    assert s.holding and s.faces == 1 and len(s.boxes) == 1, "1回目: 前の枠を残す"
    s.update(2000)
    assert s.holding and len(s.boxes) == 1, "2回目: まだ残す"
    s.update(3000)
    assert not s.holding and s.faces == 0 and s.boxes == [], "3回目: 消す"
    bus.objects = [BIRDS_JP[0]]
    s.update(4000)
    assert s.faces == 1 and not s.holding, "また見えたら、すぐに、本物の枠に戻る"


def test_no_photo_while_holding_old_boxes():
    bus, s = _bird_status([BIRDS_JP[0]], hold_polls=2, auto_photo=True, photo_cooldown_ms=0)
    for t in (0, 0, 0):
        s.update(t)
    assert bus.photo_requests == 1
    bus.objects = []
    s.update(1000)
    s.update(2000)
    assert s.holding and bus.photo_requests == 1, "前の枠を、残しているだけの間は、写真を撮らない"


def test_photo_per_visit_limit_and_reset_after_absence():
    bus, s = _bird_status([BIRDS_JP[0]], hold_polls=0, auto_photo=True, photo_cooldown_ms=30000,
                          photo_per_visit=3, absent_reset_ms=5000)
    for t in (0, 0, 0):
        s.update(t)
    assert bus.photo_requests == 1, "見えた最初の1枚は、すぐ撮る"
    for t in range(1000, 29000, 1000):
        s.update(t)
    assert bus.photo_requests == 1, "30秒たつまでは、撮らない"
    for t in range(29000, 91000, 1000):
        s.update(t)
    assert bus.photo_requests == 3, "居続けても、1回の滞在は3枚まで(30秒ごと)"
    bus.objects = []
    s.update(91000)
    s.update(93000)
    bus.objects = [BIRDS_JP[0]]
    s.update(94000)
    assert bus.photo_requests == 3, "見えなくなって5秒たたないうちに戻ったら、同じ滞在のまま(撮らない)"
    bus.objects = []
    s.update(95000)
    s.update(100500)
    s.update(101000)
    bus.objects = [BIRDS_JP[0]]
    s.update(102000)
    assert bus.photo_requests == 4, "5秒以上見えなくなったあとに戻ったら、すぐ1枚撮る"


def test_set_algo_clears_held_boxes():
    bus, s = _bird_status([BIRDS_JP[0]], hold_polls=2)
    for t in (0, 0, 0):
        s.update(t)
    bus.objects = []
    s.update(1000)
    assert s.holding
    s.set_algo(hl.ALGO_FACE_RECOGNITION, None, 2000)
    assert not s.holding and s.boxes == []


def test_bird_screen_with_japanese_names():
    bus, fake = _bird_run(BIRDS_JP)
    check_layout(fake)
    assert last_text_at(fake, 164, 28) == "x2"
    assert last_text_at(fake, 164, 102) == "bird,pers", "日本語の名前は、英語に直して出す"


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
