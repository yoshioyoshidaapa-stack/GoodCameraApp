# ESP32 側: HuskyLens2 の認識の結果を、大きく画面に出す(時計の表示はやめた)
#
# モードが 2 つある(BOOT ボタンを 4 秒以上 押し続けると切り替わる。選んだ方は mode.txt に残る):
#   bird = 物体認識(80種類)で「bird(鳥)」を探して、画面の左の「地図」に、鳥の位置を描く(既定)
#   face = 顔認識(下に説明する画面)
#
# 鳥の画面 (240x135): 左 = カメラの視野(640x480)を 1/4 にした地図と、鳥の黄色い枠。
#   右 = BIRD / 鳥の数 / 自信 / 位置(x, y) / 写真 / 見えた物の名前 / BLE
#
# 以下は、顔認識の画面:
#
# 画面 (240x135):
#   y=2   人の呼び名 (大: 文字数に応じて 4/3/2 倍。例: ID01 / unknown / NEW ID01 / SHOOT)
#   y=40  色の帯 (人(ID)ごとに色が変わる。顔がなければ灰色。撮影中は白)
#   y=52  顔の数と ID の一覧 (中: 例 n=2 ids=1,0)
#   y=72  写真の状態 (中: PHOTO ON 3)
#   y=92  自動登録の状態 (中: ENR ON 2)
#   y=110 詳細 (小: 位置・エラーなど)
#   y=122 Bluetooth の状態 (小)
#
# 写真を撮る(約0.3〜2.5秒かかる)直前に、画面の一番上に「SHOOT」を出す。
#
# BOOT ボタン(基板の「BOOT」):
#   短く押す          = 見えたら写真を撮る機能の、入り切り
#   1.5〜4秒押す      = 知らない顔を自動で覚えさせる(ID01, ID02 …)機能の、入り切り(顔認識のとき)
#   4秒以上押し続ける = 鳥 <-> 顔 のモード切り替え
#
# 時刻は、いまも BLE で受け取れる(Android アプリはそのまま使える)が、画面には出さない。
#
# 基板に入れるファイル: lcd.py, hl_status.py, huskylens2.py (なくても動く), main.py

import bluetooth
import machine
import time
import lcd

try:
    import hl_status
except ImportError:
    hl_status = None

try:
    import drive as drive_mod
except ImportError:
    drive_mod = None

_IRQ_CENTRAL_CONNECT = 1
_IRQ_CENTRAL_DISCONNECT = 2
_IRQ_GATTS_WRITE = 3

_FLAG_WRITE_NO_RESPONSE = 0x0004
_FLAG_WRITE = 0x0008

NAME = "ESP32CLK"
SVC_STR = "7d1c0001-7a3e-4c55-9f0b-5b2a1d6e0001"
CHR_STR = "7d1c0002-7a3e-4c55-9f0b-5b2a1d6e0001"

_SERVICE = (
    bluetooth.UUID(SVC_STR),
    ((bluetooth.UUID(CHR_STR), _FLAG_WRITE | _FLAG_WRITE_NO_RESPONSE),),
)

GRAY = 0x4208  # 暗い灰色
MAGENTA = 0xF81F
ORANGE = 0xFD20
HELD_GRAY = 0x8410  # 検出が途切れて、前の枠を残している間の、枠の色


def uuid128_le(s):
    h = s.replace("-", "")
    return bytes(int(h[i:i + 2], 16) for i in range(30, -2, -2))


class Clock:
    def __init__(self):
        self.synced = False
        self.conn = None
        self.last_error = ""
        self.ble = bluetooth.BLE()
        self.ble.active(True)
        self.ble.irq(self._irq)
        ((self.h_time,),) = self.ble.gatts_register_services((_SERVICE,))
        self._advertise()

    def _advertise(self):
        adv = bytes([2, 0x01, 0x06]) + bytes([len(NAME) + 1, 0x09]) + NAME.encode()
        resp = bytes([17, 0x07]) + uuid128_le(SVC_STR)
        self.ble.gap_advertise(100000, adv_data=adv, resp_data=resp)

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            self.conn = data[0]
        elif event == _IRQ_CENTRAL_DISCONNECT:
            self.conn = None
            self._advertise()
        elif event == _IRQ_GATTS_WRITE:
            if data[1] == self.h_time:
                self._set_time(self.ble.gatts_read(self.h_time))

    def _set_time(self, value):
        # 受け取る形: "YYYY-MM-DD HH:MM:SS"
        try:
            s = bytes(value).decode().strip()
            y = int(s[0:4])
            mo = int(s[5:7])
            d = int(s[8:10])
            h = int(s[11:13])
            mi = int(s[14:16])
            sec = int(s[17:19])
            machine.RTC().datetime((y, mo, d, 0, h, mi, sec, 0))
            self.synced = True
            self.last_error = ""
        except Exception:
            self.last_error = "bad data"


def pad(s, n):
    """画面の古い文字が残らないよう、決まった長さまで空白で埋める。"""
    return (s + " " * n)[:n]


MODE_FILE = "mode.txt"
MAP_X, MAP_Y, MAP_W, MAP_H = 0, 8, 160, 120  # 鳥の画面: カメラの視野(640x480)を 1/4 にした地図の場所


def load_mode():
    """前回選んだモード(bird / face)を読む。なければ bird。"""
    try:
        with open(MODE_FILE) as f:
            m = f.read().strip()
        return m if m in ("bird", "face") else "bird"
    except OSError:
        return "bird"


def save_mode(mode):
    try:
        with open(MODE_FILE, "w") as f:
            f.write(mode)
    except OSError:
        pass


def make_husky(mode="face"):
    """HuskyLens2 の状態表示を用意する。部品がなくても、動きは止めない。"""
    if hl_status is None:
        return None
    # ESP32 が再起動したとき(ENボタンなど)、通信の途中で止まった HuskyLens2 を、放してやる
    try:
        hl_status.bus_clear(machine.Pin, 21, 22, time.sleep_us)
    except Exception:
        pass
    time.sleep_ms(1000)  # 線が落ち着くまで、少し待ってから I2C を使い始める
    try:
        i2c = machine.I2C(0, sda=machine.Pin(21), scl=machine.Pin(22), freq=100000)
    except Exception:
        i2c = None
    algo, target = hl_status.mode_settings(mode)
    # 結果を読む間隔(ミリ秒)。小さいほど動きが滑らか。遅いと感じたら小さく、不安定なら大きくする。
    # 間隔を短くした分、「見失っても枠を残す回数」と「接続切れとみなす連続失敗回数」を増やして、時間は同じくらいに保つ。
    if mode == "bird":
        # 鳥: 居続ける間は 30 秒ごとに撮り、1回の滞在で 10 枚まで。5 秒見えなければ滞在は終わり。合計の上限は 1000 枚。
        return hl_status.HuskyStatus(i2c, algo=algo, target=target, poll_ms=100, hold_polls=8, max_miss=20,
                                     photo_cooldown_ms=30000, photo_per_visit=10, absent_reset_ms=5000,
                                     photo_max=1000)
    return hl_status.HuskyStatus(i2c, algo=algo, target=target, poll_ms=100, hold_polls=8, max_miss=20)


def big_scale(text):
    """文字数に応じて、いちばん大きく出せる倍率を選ぶ(横 240 ピクセルに収まる範囲)。"""
    n = len(text)
    if n <= 7:
        return 4
    if n <= 10:
        return 3
    return 2


def main():
    lcd.init()
    lcd.clear()
    clock = Clock()  # BLE で時刻を受け取る(画面には出さない)
    state = {"mode": load_mode()}
    husky = make_husky(state["mode"])
    colors = {
        "red": lcd.RED, "yellow": lcd.YELLOW, "green": lcd.GREEN, "blue": lcd.BLUE,
        "white": lcd.WHITE, "cyan": lcd.CYAN, "magenta": MAGENTA, "orange": ORANGE, "gray": GRAY,
    }
    cache = {}

    def row(key, s, y, col, scale, x=0, width=240):
        """文字が変わったときだけ描く。同じ行の倍率が変わったときは、行を黒で消してから描く。"""
        v = (s, col, scale)
        old = cache.get(key)
        if old == v:
            return
        if old is not None and old[2] != scale:
            lcd.rect(x, y, width, 8 * max(old[2], scale), lcd.BLACK)
        cache[key] = v
        lcd.text(s, x, y, col, scale)

    def bar(color):
        if cache.get("bar") != color:
            cache["bar"] = color
            lcd.rect(0, 40, 240, 8, color)

    def draw_face():
        """顔認識の画面を描く(変わったところだけ)。"""
        if husky is not None:
            label, label_color, extra = husky.view()
            bar_color = colors[husky.person_color()]
            summary = husky.summary()
            photo = husky.photo_short()
            enr = husky.enroll_line()
            if husky.shoot_show:  # 写真を撮っている間は「SHOOT」
                label, label_color, bar_color = "SHOOT", lcd.WHITE, lcd.WHITE
        else:
            label, label_color, extra = "no driver", lcd.RED, "hl_status.py missing"
            bar_color, summary, photo, enr = GRAY, "", "PHOTO -", "ENR -"
        if husky is not None and label_color in colors:
            label_color = colors[label_color]
        scale = big_scale(label)
        chars = 240 // (8 * scale)
        row("label", pad(label, chars), 2, label_color, scale)
        bar(bar_color)
        row("summary", pad(summary, 15), 52, lcd.WHITE, 2)
        row("photo", pad(photo, 15), 72, lcd.YELLOW, 2)
        row("enr", pad(enr, 15), 92, lcd.CYAN, 2)
        row("extra", pad(extra, 30), 110, lcd.WHITE, 1)
        if clock.conn is not None:
            ble = "BLE: connected"
        elif clock.last_error:
            ble = "ERR: " + clock.last_error
        else:
            ble = "BLE: waiting..."
        row("ble", pad(ble, 30), 122, lcd.GREEN, 1)

    def map_box(x, y, w, h, color):
        """鳥の枠を、地図に描く。x, y = 枠の中心、w, h = 幅・高さ (どれも 640x480 の座標)。"""
        cx = MAP_X + x // 4
        cy = MAP_Y + y // 4
        bw = max(w // 4, 6)
        bh = max(h // 4, 6)
        x0 = max(MAP_X + 1, cx - bw // 2)
        y0 = max(MAP_Y + 1, cy - bh // 2)
        x1 = min(MAP_X + MAP_W - 2, cx + bw // 2)
        y1 = min(MAP_Y + MAP_H - 2, cy + bh // 2)
        if x1 <= x0 or y1 <= y0:
            return
        lcd.rect(x0, y0, x1 - x0 + 1, 2, color)
        lcd.rect(x0, y1 - 1, x1 - x0 + 1, 2, color)
        lcd.rect(x0, y0, 2, y1 - y0 + 1, color)
        lcd.rect(x1 - 1, y0, 2, y1 - y0 + 1, color)
        lcd.rect(max(MAP_X + 1, min(cx - 1, MAP_X + MAP_W - 4)), max(MAP_Y + 1, min(cy - 1, MAP_Y + MAP_H - 4)), 3, 3, color)

    def to_screen(name):
        """"map"(地図の画面) か "wait"(つながるまでの文字だけの画面) に切り替わるとき、全部を消す。"""
        if cache.get("screen") != name:
            lcd.rect(0, 0, 240, 135, lcd.BLACK)
            cache.clear()
            cache["screen"] = name

    def draw_bird():
        """鳥の画面を描く: 左=地図と鳥の枠、右=数・自信・位置など(変わったところだけ)。"""
        if husky is None:
            to_screen("wait")
            row("w_label", pad("no driver", 10), 50, lcd.RED, 2, 0, 160)
            row("w_hint", pad("hl_status.py missing", 20), 76, lcd.WHITE, 1, 0, 160)
            return
        if not husky.is_ready():
            label, color, hint = husky.view()
            to_screen("wait")
            row("w_label", pad(label, 10), 50, colors[color], 2, 0, 160)
            row("w_hint", pad(hint, 20), 76, lcd.WHITE, 1, 0, 160)
            return
        to_screen("map")
        boxes = husky.boxes
        shoot = husky.shoot_show
        held = husky.holding  # 検出が途切れて、前の枠を残している間は、灰色で出す
        sig = (tuple([(b[0] // 8, b[1] // 8, b[2] // 8, b[3] // 8) for b in boxes]), shoot, held)
        if cache.get("map") != sig:  # 鳥の位置が変わったときだけ、地図を描き直す
            cache["map"] = sig
            lcd.rect(MAP_X, MAP_Y, MAP_W, MAP_H, lcd.BLACK)
            lcd.rect(MAP_X, MAP_Y, MAP_W, 1, GRAY)
            lcd.rect(MAP_X, MAP_Y + MAP_H - 1, MAP_W, 1, GRAY)
            lcd.rect(MAP_X, MAP_Y, 1, MAP_H, GRAY)
            lcd.rect(MAP_X + MAP_W - 1, MAP_Y, 1, MAP_H, GRAY)
            for b in boxes:
                map_box(b[0], b[1], b[2], b[3], HELD_GRAY if held else lcd.YELLOW)
            if shoot:  # 写真を撮っている間は「SHOOT」
                lcd.text("SHOOT", 20, 56, lcd.WHITE, 3)
        n = husky.faces
        first = husky.first
        conf = ""
        pos = ""
        if first is not None:
            c = first["conf"]
            conf = "%d%%" % int(c * 100 if c <= 1 else c)
            pos = "x%d y%d" % (first["x"], first["y"])
        row("b_title", "BIRD", 8, lcd.CYAN, 2, 164, 76)
        row("b_count", pad("x%d" % n, 3), 28, lcd.YELLOW if (n and not held) else GRAY, 3, 164, 76)
        row("b_conf", pad(conf, 4), 56, lcd.WHITE, 2, 164, 76)
        row("b_pos", pad(pos, 9), 78, lcd.WHITE, 1, 164, 76)
        row("b_photo", pad("PH:%s %d" % ("ON" if husky.auto_photo else "OFF", husky.photos), 9), 90, lcd.YELLOW, 1, 164, 76)
        row("b_seen", pad(husky.seen, 9), 102, lcd.GREEN, 1, 164, 76)
        if drive is not None:  # 走行の状態: DR:OFF(動かない) / DR:ON FWD(前進) LFT(左) RGT(右) STP(停止)
            short = {"forward": "FWD", "left": "LFT", "right": "RGT"}.get(drive.cmd, "STP")
            if planner.scouting:
                short = "S-" + short[0]  # 偵察中(探して回っている): S-L / S-R / S-F / S-S(止まって見ている)
            row("b_ble", pad("DR:ON " + short if drive.enabled else "DR:OFF", 9), 114,
                lcd.RED if drive.enabled else GRAY, 1, 164, 76)
        else:
            row("b_ble", pad("BLE ok" if clock.conn is not None else "BLE ..", 9), 114, lcd.GREEN, 1, 164, 76)

    def draw():
        """いまのモードの画面を描く。写真を撮る直前にも呼ばれる。"""
        if state["mode"] == "bird":
            draw_bird()
        else:
            to_screen("face")
            draw_face()

    def switch_mode():
        """鳥 <-> 顔 を切り替える。選んだモードは、基板に残す(電源を入れ直しても、そのまま)。"""
        state["mode"] = "face" if state["mode"] == "bird" else "bird"
        save_mode(state["mode"])
        if husky is not None:
            algo, target = hl_status.mode_settings(state["mode"])
            husky.set_algo(algo, target, time.ticks_ms())
        lcd.rect(0, 0, 240, 135, lcd.BLACK)
        cache.clear()

    drive = None
    planner = None
    if drive_mod is not None:
        try:
            def _pwm(pin):
                p = machine.PWM(machine.Pin(pin), freq=50)
                p.duty_u16(0)
                return p
            drive = drive_mod.Drive(_pwm)
            planner = drive_mod.Planner()
        except Exception:
            drive = None  # サーボが使えなくても、画面は動かす

    def shoot_draw():
        # 写真を撮っている間(最大数秒)は、結果が読めず、動きを直せない。先に止める。
        if drive is not None:
            drive.stop()
        draw()

    if husky is not None:
        husky.shoot_cb = shoot_draw
    boot = machine.Pin(0, machine.Pin.IN, machine.Pin.PULL_UP)  # BOOT ボタン
    boot_down_at = None  # BOOT ボタンを押し始めた時刻

    while True:
        if husky is not None:
            husky.update(time.ticks_ms())
            # BOOT ボタン: 短押しは写真の入り切り、1.5秒以上の長押しは自動登録の入り切り(離したときに判定)
            if boot.value() == 0:
                if boot_down_at is None:
                    boot_down_at = time.ticks_ms()
            elif boot_down_at is not None:
                held = time.ticks_diff(time.ticks_ms(), boot_down_at)
                boot_down_at = None
                if held >= 4000:
                    if drive is not None:
                        drive.stop()
                    switch_mode()
                elif held >= 1500:
                    if drive is not None and state["mode"] == "bird":
                        if drive.toggle():  # 鳥モードでは、走行の入り切り(顔モードでは、自動登録の入り切り)
                            planner.reset(time.ticks_ms())  # 入れたら、見失った状態から数え直す
                    else:
                        husky.toggle_enroll()
                elif held >= 30:
                    husky.toggle_photo()
        if drive is not None:
            if state["mode"] == "bird":
                drive.command(planner.update(husky, time.ticks_ms(), drive.cmd))
            else:
                drive.stop()
                planner.reset(time.ticks_ms())
        draw()
        time.sleep_ms(100)


main()
