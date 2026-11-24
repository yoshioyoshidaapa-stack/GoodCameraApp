# ESP32 側: BLE で時刻を受け取って、画面に時計を出す
import bluetooth
import machine
import time
import lcd

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
        except Exception as e:
            self.last_error = "bad data"


def main():
    lcd.init()
    lcd.clear()
    clock = Clock()
    last_sec = -1
    last_state = None
    while True:
        t = time.localtime()
        state = (clock.synced, clock.conn is not None, clock.last_error)
        if t[5] != last_sec or state != last_state:
            last_sec = t[5]
            last_state = state
            if clock.synced:
                hhmmss = "%02d:%02d:%02d" % (t[3], t[4], t[5])
                date = "%04d-%02d-%02d" % (t[0], t[1], t[2])
                week = WEEK[t[6]]
                col = lcd.CYAN
            else:
                hhmmss = "--:--:--"
                date = "----------"
                week = "NO SYNC"
                col = lcd.RED
            lcd.text(hhmmss, 24, 16, col, 3)
            lcd.text(date, 40, 56, lcd.WHITE, 2)
            lcd.text(week + "     ", 40, 80, lcd.YELLOW, 2)
            if clock.conn is not None:
                status = "BLE: connected   "
            elif clock.last_error:
                status = "ERR: " + clock.last_error + "      "
            else:
                status = "BLE: waiting...  "
            lcd.text(status, 8, 116, lcd.GREEN, 1)
        time.sleep_ms(100)


main()
