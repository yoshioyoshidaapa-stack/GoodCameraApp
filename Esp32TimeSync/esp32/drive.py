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
# 偵察(鳥が画面にいないとき、探して回る)。時間はミリ秒。実物に合わせて直す。
LOST_MS = 3000  # 鳥を見失ってから、この時間は、止まって待つ(すぐ探しに行かない)
TURN_MS = 500  # 探すとき、1回に回る時間
LOOK_MS = 1200  # 回ったあと、止まって見る時間(動いている間は、画像がぶれて、見つかりにくい)
STEPS_PER_TURN = 12  # 「回る→見る」を、この回数やったら(だいたい1周)、少し前進する
FORWARD_MS = 2000  # 1周しても見つからないとき、前進する時間
# 前方の壁よけ(超音波距離センサー HC-SR04 など)
PIN_TRIG = 27  # センサーの TRIG をつなぐ GPIO 番号
PIN_ECHO = 33  # センサーの ECHO をつなぐ GPIO 番号(★5V の ECHO は、抵抗2本で 3.3V 以下に下げてから)
STOP_CM = 25  # これより近いものが前にあれば、前進をやめる(センチ)
CONFIRM_N = 2  # 近いという測定が、続けて何回出たら、本当に近いと判断するか(ノイズ対策)
BACK_MS = 1000  # ぶつかりそうなとき、後ろに下がる時間
AVOID_TURN_MS = 1000  # 下がったあと、壁から離れる向きに、最低これだけ回る時間
CLEAR_CM = 60  # 前がこれより遠くなるまで、回り続ける(角に追い込まれても、抜け出せるように)
AVOID_TURN_MAX_MS = 4000  # 回り続ける時間の上限(出口が見つからなくても、止まらず次へ進む)

try:
    from time import ticks_diff
except ImportError:  # パソコンの Python で試験するとき
    def ticks_diff(a, b):
        return a - b


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
    if cmd == "back":
        return (-1, -1)
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


def _track(husky, current):
    """鳥が見えているときの動き。検出が一瞬途切れて枠を残している間(holding)は、今の動きを続ける。"""
    if husky.holding:
        return current
    first = husky.first
    if husky.faces <= 0 or first is None:
        return None  # 鳥はいない
    return decide(first["x"], first["w"])


class Sonar:
    """超音波距離センサー。measure() で、前にあるものまでの距離(センチ)を返す。何も返ってこなければ None。"""

    def __init__(self, trig, echo, pulse_fn, sleep_us, timeout_us=15000):
        self.trig = trig
        self.echo = echo
        self.pulse_fn = pulse_fn  # machine.time_pulse_us
        self.sleep_us = sleep_us
        self.timeout_us = timeout_us  # 15 ms = 約2.5 m まで

    def measure(self):
        self.trig.value(0)
        self.sleep_us(2)
        self.trig.value(1)
        self.sleep_us(10)
        self.trig.value(0)
        t = self.pulse_fn(self.echo, 1, self.timeout_us)
        if t is None or t <= 0:
            return None
        return t / 58.0  # 音が往復する時間 → 距離(cm)


class Planner:
    """見えているときは追いかけ、見えなくなったら、しばらく待ってから、回って探す(偵察)。"""

    def __init__(self, lost_ms=LOST_MS, turn_ms=TURN_MS, look_ms=LOOK_MS, steps=STEPS_PER_TURN,
                 forward_ms=FORWARD_MS, img_w=IMG_W, stop_cm=STOP_CM, confirm_n=CONFIRM_N,
                 back_ms=BACK_MS, avoid_turn_ms=AVOID_TURN_MS, clear_cm=CLEAR_CM,
                 avoid_turn_max_ms=AVOID_TURN_MAX_MS):
        self.lost_ms = lost_ms
        self.turn_ms = turn_ms
        self.look_ms = look_ms
        self.steps = steps
        self.forward_ms = forward_ms
        self.img_w = img_w
        self.stop_cm = stop_cm
        self.confirm_n = confirm_n
        self.back_ms = back_ms
        self.avoid_turn_ms = avoid_turn_ms
        self.clear_cm = clear_cm
        self.avoid_turn_max_ms = avoid_turn_max_ms
        self.reset(0)

    def reset(self, now):
        self.last_seen = now  # 見失った(または、探し始めた)時刻
        self.side = 1  # 探すときに回る向き: 1 = 右 / -1 = 左(最後に鳥が見えた側を向く)
        self.scouting = False
        self.phase = "turn"
        self.phase_start = now
        self.step_n = 0
        self.avoiding = False  # 壁をよけている最中
        self.avoid_phase = "back"
        self.avoid_start = now
        self._near_n = 0
        self.blocked = False  # 前に、近いものがある

    def _update_blocked(self, dist_cm):
        if dist_cm is not None and dist_cm < self.stop_cm:
            self._near_n += 1
        else:
            self._near_n = 0  # 測れなかった(遠い)ときも、近くないとみなす
        self.blocked = self._near_n >= self.confirm_n

    def _turn_cmd(self):
        return "right" if self.side > 0 else "left"

    def update(self, husky, now, current, dist_cm=None):
        """いまの動き ('forward' / 'back' / 'left' / 'right' / 'stop') を返す。
        dist_cm: 前方の距離センサーの値(センチ)。なし(None)なら、壁よけはしない。"""
        self._update_blocked(dist_cm)
        if husky is None or not husky.is_ready():
            self.reset(now)  # つながっていない間は止まる。つながったら、待ってから探し始める
            return "stop"
        if self.avoiding:  # 壁をよけている最中は、これを最後までやる: 下がる → 離れる向きに回る
            elapsed = ticks_diff(now, self.avoid_start)
            if self.avoid_phase == "back":
                if elapsed < self.back_ms:
                    return "back"
                self.avoid_phase = "turn"
                self.avoid_start = now
                return self._turn_cmd()
            if elapsed < self.avoid_turn_ms:
                return self._turn_cmd()
            if elapsed < self.avoid_turn_max_ms and dist_cm is not None and dist_cm < self.clear_cm:
                return self._turn_cmd()  # まだ前が近い: 空いている方向を向くまで、回り続ける
            self.avoiding = False  # よけ終わり: 探すのを、見る(止まる)ところから再開
            self.phase = "look"
            self.phase_start = now
            self.step_n = 0
            return "stop"
        cmd = _track(husky, current)
        if cmd is not None:  # 鳥が見えている
            self.last_seen = now
            self.scouting = False
            first = husky.first
            if first is not None:
                self.side = 1 if first["x"] >= self.img_w // 2 else -1
            if cmd == "forward" and self.blocked:
                return "stop"  # 鳥は前にいるが、手前に壁などがある: ぶつからないよう止まる
            return cmd
        if ticks_diff(now, self.last_seen) < self.lost_ms:
            return "stop"  # 見失った直後は、止まって待つ
        if not self.scouting:
            self.scouting = True
            self.phase = "turn"
            self.phase_start = now
            self.step_n = 0
        elapsed = ticks_diff(now, self.phase_start)
        if self.phase == "turn":
            if elapsed >= self.turn_ms:
                self.phase = "look"
                self.phase_start = now
                return "stop"
            return "right" if self.side > 0 else "left"
        if self.phase == "look":
            if elapsed >= self.look_ms:
                self.step_n += 1
                self.phase_start = now
                if self.step_n >= self.steps:
                    self.phase = "forward"
                    self.step_n = 0
                    return "forward"
                self.phase = "turn"
                return "right" if self.side > 0 else "left"
            return "stop"
        if self.blocked:  # 前進中に、前が近い: 下がって、壁から離れる向きに回る(毎回、向きを替えて、角にはまらないように)
            self.avoiding = True
            self.avoid_phase = "back"
            self.avoid_start = now
            self.side = -self.side
            return "back"
        if elapsed >= self.forward_ms:  # forward
            self.phase = "turn"
            self.phase_start = now
            return "right" if self.side > 0 else "left"
        return "forward"
