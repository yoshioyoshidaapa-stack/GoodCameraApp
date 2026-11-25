# ESP32 側: BLE で時刻を受け取って、画面に時計と HuskyLens2 の状態を出す
#
# 画面 (240x135):
#   y=4   時刻 HH:MM:SS (大)
#   y=34  日付と曜日 / 時刻が未同期のときは赤い NO SYNC
#   y=58  区切り線
#   y=64  HuskyLens2 の見出し
#   y=76  HuskyLens2 の状態 (not found / connecting / faces:N)
#   y=96  HuskyLens2 の詳細 (顔の位置など)
#   y=118 Bluetooth の状態
#
# 基板に入れるファイル: lcd.py, hl_status.py, huskylens2.py (なくても時計は動く), main.py

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

WEEK = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
LINE_COLOR = 0x4208  # 暗い灰色


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
    """HuskyLens2 の状態表示を用意する。部品がなくても、時計は止めない。"""
    if hl_status is None:
        return None
    try:
        i2c = machine.I2C(0, sda=machine.Pin(21), scl=machine.Pin(22), freq=100000)
    except Exception:
        i2c = None
    return hl_status.HuskyStatus(i2c)


def main():
    lcd.init()
    lcd.clear()
    lcd.rect(8, 58, 224, 1, LINE_COLOR)
    clock = Clock()
    husky = make_husky()
    colors = {"red": lcd.RED, "yellow": lcd.YELLOW, "green": lcd.GREEN}
    cache = {}

    def row(key, s, x, y, col, scale):
        v = (s, col)
        if cache.get(key) != v:
            cache[key] = v
            lcd.text(s, x, y, col, scale)

    while True:
        if husky is not None:
            husky.update(time.ticks_ms())
        t = time.localtime()
        if clock.synced:
            hhmmss = "%02d:%02d:%02d" % (t[3], t[4], t[5])
            date = "%04d-%02d-%02d %s" % (t[0], t[1], t[2], WEEK[t[6]])
            time_col, date_col = lcd.CYAN, lcd.WHITE
        else:
            hhmmss = "--:--:--"
            date = "NO SYNC"
            time_col, date_col = lcd.RED, lcd.RED
        row("time", hhmmss, 24, 4, time_col, 3)
        row("date", pad(date, 15), 0, 34, date_col, 2)

        row("hl_title", pad("HUSKYLENS 2  I2C 0x50", 30), 0, 64, lcd.CYAN, 1)
        if husky is not None:
            detail, color, extra = husky.view()
        else:
            detail, color, extra = "no driver", "red", "hl_status.py missing"
        row("hl_detail", pad(detail, 15), 0, 76, colors[color], 2)
        row("hl_extra", pad(extra, 30), 0, 96, lcd.WHITE, 1)

        if clock.conn is not None:
            ble = "BLE: connected"
        elif clock.last_error:
            ble = "ERR: " + clock.last_error
        else:
            ble = "BLE: waiting..."
        row("ble", pad(ble, 30), 0, 118, lcd.GREEN, 1)
        time.sleep_ms(100)


main()
