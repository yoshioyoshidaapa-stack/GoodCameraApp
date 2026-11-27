# クローラーの走行: 鳥が中央なら前進、右寄りなら右に回る、左寄りなら左に回る、見えなければ止まる。
# サーボ(連続回転タイプ)を2個、左右のキャタピラに使う想定。
#
# ★ 最初に、ここの数字を、実物に合わせて直す(servo_test.py で確かめる) ★
PIN_LEFT = 25  # 左のサーボの信号線をつなぐ GPIO 番号
PIN_RIGHT = 26  # 右のサーボの信号線をつなぐ GPIO 番号
STOP_US = 1500  # 止まる信号の長さ(マイクロ秒)。サーボによって、少しずれる(1450〜1550)
SPEED_US = 100  # 回る速さ。大きいほど速い(最大 500 くらい)。最初は小さく
INVERT_LEFT = False  # 左のサーボが逆に回るなら True
INVERT_RIGHT = True  # 右のサーボは、向きが左右反対につくので、ふつう True
# 判定(カメラの画面は横 640 ピクセル。中央は 320)
IMG_W = 640
CENTER_BAND = 100  # 中央から、左右にこの幅(ピクセル)の中なら「中央」(220〜420)
NEAR_W = 320  # 鳥の枠の幅がこれ以上なら、近いので止まる


def decide(x, w=0, img_w=IMG_W, band=CENTER_BAND, near_w=NEAR_W):
    """鳥の位置 x (枠の中心) と幅 w から、動きを決める: 'forward' / 'left' / 'right' / 'stop'。"""
    if x is None:
        return "stop"
    if w >= near_w:
        return "stop"  # 近づいた
    c = img_w // 2
    if x < c - band:
        return "left"
    if x > c + band:
        return "right"
    return "forward"


def wheel_signs(cmd):
    """動き -> (左, 右)。1 = 前に回す / -1 = 後ろに回す / 0 = 止める。(その場で回る: 左右を逆に回す)"""
    if cmd == "forward":
        return (1, 1)
    if cmd == "left":
        return (-1, 1)  # 左に回る = 左を後ろ、右を前
    if cmd == "right":
        return (1, -1)
    return (0, 0)


def pulse_us(sign, invert, speed=SPEED_US, stop=STOP_US):
    """回す向きと、サーボの向きの反転から、信号の長さ(マイクロ秒)を決める。0 は「信号を出さない」=止まる。"""
    if sign == 0:
        return 0
    if invert:
        sign = -sign
    return stop + sign * speed


class Drive:
    def __init__(self, pwm_factory, pin_left=PIN_LEFT, pin_right=PIN_RIGHT,
                 invert_left=INVERT_LEFT, invert_right=INVERT_RIGHT, speed=SPEED_US, stop=STOP_US):
        self.left = pwm_factory(pin_left)
        self.right = pwm_factory(pin_right)
        self.invert_left = invert_left
        self.invert_right = invert_right
        self.speed = speed
        self.stop_us = stop
        self.enabled = False  # 電源を入れた直後は、動かさない(BOOT ボタンで入れる)
        self.cmd = "stop"
        self._apply("stop")

    @staticmethod
    def _duty(us):
        return int(us * 65535 // 20000)  # 周期 20ms(50Hz)に対する、信号の長さ

    def _apply(self, cmd):
        sl, sr = wheel_signs(cmd)
        self.left.duty_u16(self._duty(pulse_us(sl, self.invert_left, self.speed, self.stop_us)))
        self.right.duty_u16(self._duty(pulse_us(sr, self.invert_right, self.speed, self.stop_us)))
        self.cmd = cmd

    def command(self, cmd):
        """動きを指示する。入り切りが「切」なら、いつでも止める。"""
        if not self.enabled:
            cmd = "stop"
        if cmd != self.cmd:
            self._apply(cmd)

    def stop(self):
        self._apply("stop")

    def toggle(self):
        self.enabled = not self.enabled
        if not self.enabled:
            self.stop()
        return self.enabled


def plan(husky, current):
    """HuskyStatus の今の状態から、動きを決める。
    読み取りが成功している間は、鳥の位置で決める。検出が一瞬途切れて枠を残している間(holding)は、今の動きを続ける。
    それ以外(つながっていない・読めない・鳥がいない)は、止まる。"""
    if husky is None or not husky.is_ready():
        return "stop"
    if husky.holding:
        return current
    first = husky.first
    if husky.faces <= 0 or first is None:
        return "stop"
    return decide(first["x"], first["w"])
