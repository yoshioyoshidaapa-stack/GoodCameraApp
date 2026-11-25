# HuskyLens2 の状態を、時計の画面に出すための小さな部品。
# 時計を止めないように、通信の待ち時間を短くして、状態を自動で進める。
#   NOT_FOUND (I2C に 0x50 がいない) -> FOUND (見つかった) -> READY (つながった・結果を読める)
# 失敗したら、NOT_FOUND に戻って、数秒ごとに探し直す。
# ※ 本物の HuskyLens2 では、まだ動かしていない。

try:
    import huskylens2 as hl
except ImportError:  # 部品が基板に入っていないとき
    hl = None

try:
    from time import ticks_diff
except ImportError:  # パソコンの Python で試験するとき
    def ticks_diff(a, b):
        return a - b

NOT_FOUND = 0
FOUND = 1
READY = 2
NO_DRIVER = 3

ADDR = 0x50


class HuskyStatus:
    def __init__(self, i2c, algo=None, scan_ms=3000, retry_ms=3000, poll_ms=1000):
        self.i2c = i2c
        self.state = NO_DRIVER if (hl is None or i2c is None) else NOT_FOUND
        self.algo = algo if algo is not None else (hl.ALGO_FACE_RECOGNITION if hl else 0)
        self.scan_ms = scan_ms
        self.retry_ms = retry_ms
        self.poll_ms = poll_ms
        self.faces = 0
        self.first = None  # 最初の結果 (辞書)
        self.err = ""  # 直近のエラー（画面に出す）
        self.step = ""  # いま何をしているか（エラーの場所の手がかり）
        self._next = 0
        # 時計を止めないよう、待ち時間は短くする
        self.cam = hl.HuskyLens2(i2c, timeout_ms=300, retry=1) if self.state != NO_DRIVER else None

    def update(self, now):
        """1回呼ぶごとに、必要な分だけ状態を進める。now は ticks_ms() の値。
        どんなエラーが起きても止まらず、エラーを self.err に残して、探し直しに戻る。"""
        if self.state == NO_DRIVER or ticks_diff(now, self._next) < 0:
            return
        try:
            self._step(now)
        except Exception as e:  # 想定外の返事などでも、時計を止めない
            self.err = ("%s:%s" % (self.step, type(e).__name__))[:30]
            self.state = NOT_FOUND
            self._next = now + self.retry_ms
            self.faces = 0
            self.first = None

    def _step(self, now):
        if self.state == NOT_FOUND:
            self._next = now + self.scan_ms
            self.step = "scan"
            try:
                found = ADDR in self.i2c.scan()
            except OSError:
                found = False
            if found:
                self.state = FOUND
                self._next = now  # すぐに接続を試す
        elif self.state == FOUND:
            ok = False
            self.step = "knock"
            try:
                ok = self.cam.knock()
                if ok:
                    self.step = "algo"
                    ok = self.cam.switch_algorithm(self.algo)
            except OSError:
                ok = False
            if ok:
                self.state = READY
                self.err = ""
                self._next = now
            else:
                self.err = (self.step + " failed")[:30]
                self.state = NOT_FOUND
                self._next = now + self.retry_ms
        elif self.state == READY:
            self._next = now + self.poll_ms
            self.step = "result"
            try:
                rs = self.cam.get_results(self.algo)
            except OSError:
                rs = None
            if rs is None:
                self.err = "result failed"
                self.state = NOT_FOUND
                self._next = now + self.retry_ms
                self.faces = 0
                self.first = None
            else:
                self.faces = len(rs)
                self.first = rs[0] if rs else None

    def view(self):
        """画面に出す内容を (状態の文字 [15字まで], 色の名前, 詳細の文字 [30字まで]) で返す。"""
        if self.state == NO_DRIVER:
            return ("no driver", "red", "huskylens2.py missing")
        if self.state == NOT_FOUND:
            if self.err:
                return ("not found", "red", self.err)
            return ("not found", "red", "check wiring / power")
        if self.state == FOUND:
            return ("connecting", "yellow", "0x50 found")
        if self.first is None:
            return ("faces:0", "green", "no face")
        r = self.first
        return ("faces:%d" % self.faces, "green", "x%d y%d %dx%d" % (r["x"], r["y"], r["w"], r["h"]))
