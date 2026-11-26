# ESP32 側: HuskyLens2 の顔認識の結果を、大きく画面に出す(時計の表示はやめた)
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
#   短く押す      = 顔が見えたら写真を撮る機能の、入り切り
#   1.5秒以上押す = 知らない顔を自動で覚えさせる(ID01, ID02 …)機能の、入り切り
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


def make_husky():
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
    return hl_status.HuskyStatus(i2c)


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
    husky = make_husky()
    colors = {
        "red": lcd.RED, "yellow": lcd.YELLOW, "green": lcd.GREEN, "blue": lcd.BLUE,
        "white": lcd.WHITE, "cyan": lcd.CYAN, "magenta": MAGENTA, "orange": ORANGE, "gray": GRAY,
    }
    cache = {}

    def row(key, s, y, col, scale):
        """文字が変わったときだけ描く。同じ行の倍率が変わったときは、行を黒で消してから描く。"""
        v = (s, col, scale)
        old = cache.get(key)
        if old == v:
            return
        if old is not None and old[2] != scale:
            lcd.rect(0, y, 240, 8 * max(old[2], scale), lcd.BLACK)
        cache[key] = v
        lcd.text(s, 0, y, col, scale)

    def bar(color):
        if cache.get("bar") != color:
            cache["bar"] = color
            lcd.rect(0, 40, 240, 8, color)

    def draw():
        """画面全体を描く(変わったところだけ)。写真を撮る直前にも呼ばれる。"""
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

    if husky is not None:
        husky.shoot_cb = draw
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
                if held >= 1500:
                    husky.toggle_enroll()
                elif held >= 30:
                    husky.toggle_photo()
        draw()
        time.sleep_ms(100)


main()
