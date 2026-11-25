from machine import Pin, SPI
import time, framebuf

W = 240
H = 135
XOFF = 40
YOFF = 53

BLACK = 0x0000
WHITE = 0xFFFF
RED = 0xF800
GREEN = 0x07E0
BLUE = 0x001F
YELLOW = 0xFFE0
CYAN = 0x07FF

_spi = SPI(2, baudrate=20000000, polarity=0, phase=0,
           sck=Pin(18), mosi=Pin(23), miso=Pin(19))
_cs = Pin(15, Pin.OUT, value=1)
_dc = Pin(2, Pin.OUT, value=0)
_rst = Pin(4, Pin.OUT, value=1)
_blk = Pin(32, Pin.OUT, value=0)


def _cmd(c, data=None):
    _cs(0)
    _dc(0)
    _spi.write(bytes([c]))
    if data is not None:
        _dc(1)
        _spi.write(data)
    _cs(1)


def _window(x0, y0, x1, y1):
    x0 += XOFF
    x1 += XOFF
    y0 += YOFF
    y1 += YOFF
    _cmd(0x2A, bytes([x0 >> 8, x0 & 255, x1 >> 8, x1 & 255]))
    _cmd(0x2B, bytes([y0 >> 8, y0 & 255, y1 >> 8, y1 & 255]))


def _write(data):
    _cs(0)
    _dc(0)
    _spi.write(b'\x2C')
    _dc(1)
    _spi.write(data)
    _cs(1)


def init():
    _rst(1)
    time.sleep_ms(10)
    _rst(0)
    time.sleep_ms(20)
    _rst(1)
    time.sleep_ms(150)
    _cmd(0x01)
    time.sleep_ms(150)
    _cmd(0x11)
    time.sleep_ms(120)
    _cmd(0x3A, b'\x55')
    _cmd(0x21)
    _cmd(0x13)
    time.sleep_ms(10)
    # 画面用メモリ全体(240x320)を黒で塗る。前の表示の白い線が残らないようにする
    _cmd(0x36, b'\x00')
    _cmd(0x2A, bytes([0, 0, 0, 239]))
    _cmd(0x2B, bytes([0, 0, 1, 63]))
    _cs(0)
    _dc(0)
    _spi.write(b'\x2C')
    _dc(1)
    black = bytes(480)
    for _ in range(320):
        _spi.write(black)
    _cs(1)
    # 横向きにして、画面を表示する
    _cmd(0x36, b'\xA0')
    _cmd(0x29)
    time.sleep_ms(100)
    _blk(1)


def rect(x, y, w, h, color):
    if w <= 0 or h <= 0:
        return
    hi = color >> 8
    lo = color & 255
    _window(x, y, x + w - 1, y + h - 1)
    _cs(0)
    _dc(0)
    _spi.write(b'\x2C')
    _dc(1)
    line = bytes([hi, lo]) * w
    for _ in range(h):
        _spi.write(line)
    _cs(1)


def clear(color=BLACK):
    rect(0, 0, W, H, color)


def text(s, x, y, color=WHITE, scale=2, bg=BLACK):
    fg_hi = color >> 8
    fg_lo = color & 255
    bg_hi = bg >> 8
    bg_lo = bg & 255
    sz = 8 * scale
    fb = framebuf.FrameBuffer(bytearray(8), 8, 8, framebuf.MONO_HLSB)
    for ch in s:
        fb.fill(0)
        fb.text(ch, 0, 0, 1)
        buf = bytearray(sz * sz * 2)
        for i in range(0, sz * sz * 2, 2):
            buf[i] = bg_hi
            buf[i + 1] = bg_lo
        for row in range(8):
            for col in range(8):
                if fb.pixel(col, row):
                    for dy in range(scale):
                        for dx in range(scale):
                            i = ((row * scale + dy) * sz + col * scale + dx) * 2
                            buf[i] = fg_hi
                            buf[i + 1] = fg_lo
        if x + sz > W or y + sz > H:
            x += sz
            continue
        _window(x, y, x + sz - 1, y + sz - 1)
        _write(buf)
        x += sz
