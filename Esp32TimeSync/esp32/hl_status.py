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

# 人(ID)ごとの色。ID 0 は「まだ覚えていない人」。ID が多いときは、最初に戻って繰り返す。
ID_COLORS = ("white", "red", "green", "blue", "yellow", "magenta", "cyan", "orange")


def id_color(person_id):
    return ID_COLORS[person_id % len(ID_COLORS)]


def person_label(r):
    """画面の一番大きい行に出す、人の呼び名(15字まで)。
    ID 0 = まだ覚えていない顔 -> unknown / 名前が付いていれば名前 / なければ ID 番号。
    ※ 名前の初期値 "Face" と、画面の文字(英数字のみ)で出せない名前は、使わない。"""
    if r["id"] == 0:
        return "unknown"
    name = r["name"]
    if name and name != "Face" and all(32 <= ord(c) < 127 for c in name):
        return name[:15]
    return "ID%02d" % r["id"]


def main_result(results):
    """複数の顔のうち、いちばん大きい顔(=いちばん近い人)を選ぶ。"""
    best = None
    for r in results:
        if best is None or r["w"] * r["h"] > best["w"] * best["h"]:
            best = r
    return best


class HuskyStatus:
    def __init__(self, i2c, algo=None, scan_ms=3000, retry_ms=3000, poll_ms=1000,
                 settle_ms=2000, max_miss=5,
                 auto_photo=True, photo_cooldown_ms=10000, photo_max=50, photo_res=1,
                 auto_enroll=True, enroll_max=8, enroll_cooldown_ms=10000, enroll_stable=3):
        self.i2c = i2c
        self.state = NO_DRIVER if (hl is None or i2c is None) else NOT_FOUND
        self.algo = algo if algo is not None else (hl.ALGO_FACE_RECOGNITION if hl else 0)
        self.scan_ms = scan_ms
        self.retry_ms = retry_ms
        self.poll_ms = poll_ms
        # 顔認識に切り替えた直後は、HuskyLens2 の準備を待つ。返事が少し遅れても、すぐには切らない。
        self.settle_ms = settle_ms
        self.max_miss = max_miss
        self.miss = 0  # 結果を読めなかった、連続の回数
        self.polled = False  # READY になってから、結果を、一度でも読めたか
        self.faces = 0
        self.ids = []  # 見えている顔の ID の一覧(最大6件)
        self.first = None  # いちばん大きい顔の結果 (辞書)
        # 顔が見えたら写真を撮る(撮りすぎないよう、間隔と上限を決めてある)
        self.auto_photo = auto_photo
        self.photo_cooldown_ms = photo_cooldown_ms
        self.photo_max = photo_max
        self.photo_res = photo_res  # 1 = 640x480
        self.photos = 0  # 電源を入れてから撮った枚数
        self.last_photo = ""  # 直近のファイル名
        self.photo_fail = False
        # 写真を撮っている間と、その直後(次に結果を読むまで)は True。画面に「SHOOT」を出す目印。
        self.shoot_show = False
        # 写真を撮る「直前」に呼ばれる関数。撮影の間は画面の更新が止まるので、先に「SHOOT」を描く。
        self.shoot_cb = None
        self._last_photo_ms = None
        # 知らない顔(ID 0)が、画面の中央にじっといたら、自動で覚えさせる(ID01, ID02 … の順)
        self.auto_enroll = auto_enroll
        self.enroll_max = enroll_max  # 電源を入れてから、自動で覚えさせる人数の上限
        self.enroll_cooldown_ms = enroll_cooldown_ms  # 次に覚えさせるまでの、最低の間隔
        self.enroll_stable = enroll_stable  # 条件が、続けて何回(=何秒)そろったら覚えさせるか
        self.enrolled = 0  # 電源を入れてから、自動で覚えさせた人数
        self.new_id = 0  # 直前に付いた ID(数秒だけ「NEW ID」と表示する)
        self.enroll_fail = False
        self._new_until = 0
        self._stable = 0
        self._last_enroll_ms = None
        self.err = ""  # 直近のエラー（画面に出す）
        self.step = ""  # いま何をしているか（エラーの場所の手がかり）
        self._next = 0
        # 時計を止めないよう、待ち時間は短くする
        self.cam = hl.HuskyLens2(i2c, timeout_ms=600, retry=1) if self.state != NO_DRIVER else None

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
                self.miss = 0
                self.polled = False
                self._next = now + self.settle_ms  # 準備ができるまで、少し待ってから、結果を読む
            else:
                self.err = (self.step + " failed")[:30]
                self.state = NOT_FOUND
                self._next = now + self.retry_ms
        elif self.state == READY:
            self._next = now + self.poll_ms
            self.step = "result"
            self.shoot_show = False  # 前回の「SHOOT」の表示は、次に結果を読むときに、終わる
            try:
                rs = self.cam.get_results(self.algo)
            except OSError:
                rs = None
            if rs is None:
                self.miss += 1
                if self.miss >= self.max_miss:  # 何回も続けて読めなければ、つなぎ直す
                    self.err = "result failed"
                    self.state = NOT_FOUND
                    self._next = now + self.retry_ms
                    self.faces = 0
                    self.ids = []
                    self.first = None
                    self.new_id = 0
                    self._stable = 0
                    self.miss = 0
            else:
                self.miss = 0
                self.polled = True
                self.faces = len(rs)
                self.ids = [r["id"] for r in rs[:6]]
                self.first = main_result(rs)
                if self.new_id and ticks_diff(now, self._new_until) >= 0:
                    self.new_id = 0
                if self.faces > 0:
                    self._maybe_take_photo(now)
                self._maybe_enroll(now)

    def toggle_enroll(self):
        """自動で覚えさせる機能の、入り切り。"""
        self.auto_enroll = not self.auto_enroll
        self._stable = 0
        return self.auto_enroll

    def _maybe_enroll(self, now):
        r = self.first
        # 覚えさせてよい顔: 見えているのが1人だけで、まだ覚えていない顔(ID 0)で、
        # ある程度大きく、画面の中央の十字が、顔の枠の中にある(本体の Button-A と同じ条件)
        ok = (self.auto_enroll and self.enrolled < self.enroll_max and self.faces == 1
              and r is not None and r["id"] == 0 and r["w"] >= 80
              and abs(r["x"] - 320) <= r["w"] // 2 and abs(r["y"] - 240) <= r["h"] // 2)
        if not ok:
            self._stable = 0
            return
        if self._last_enroll_ms is not None and ticks_diff(now, self._last_enroll_ms) < self.enroll_cooldown_ms:
            return
        self._stable += 1
        if self._stable < self.enroll_stable:
            return
        self._stable = 0
        self._last_enroll_ms = now  # 失敗しても、すぐには、やり直さない
        self.step = "learn"
        try:
            new_id = self.cam.learn(self.algo, timeout_ms=3000)
        except OSError:
            new_id = 0
        if new_id > 0:
            self.enrolled += 1
            self.new_id = new_id
            self._new_until = now + 5000
            self.enroll_fail = False
        else:
            self.enroll_fail = True

    def enroll_line(self):
        """画面の一番下に出す、自動登録の状態(12字まで)。"""
        if self.state == NO_DRIVER:
            return "ENR -"
        if self.enroll_fail and self.auto_enroll:
            return "ENR FAIL"
        return "ENR %s %d" % ("ON" if self.auto_enroll else "OFF", self.enrolled)

    def toggle_photo(self):
        """写真を撮る機能の、入り切り。"""
        self.auto_photo = not self.auto_photo
        return self.auto_photo

    def _maybe_take_photo(self, now):
        if not self.auto_photo or self.photos >= self.photo_max:
            return
        if self._last_photo_ms is not None and ticks_diff(now, self._last_photo_ms) < self.photo_cooldown_ms:
            return
        self._last_photo_ms = now  # 失敗しても、すぐには撮り直さない
        self.step = "photo"
        self.shoot_show = True
        if self.shoot_cb is not None:
            try:
                self.shoot_cb()  # 撮影の間は画面が止まるので、先に「SHOOT」を描く
            except Exception:
                pass
        try:
            name = self.cam.take_photo(self.photo_res, timeout_ms=2500)
        except OSError:
            name = ""
        if name:
            self.photos += 1
            self.last_photo = name
            self.photo_fail = False
        else:
            self.photo_fail = True

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
        if self.miss > 0:
            return ("no reply", "yellow", "miss %d/%d" % (self.miss, self.max_miss))
        if not self.polled:
            return ("starting", "yellow", "waiting for HuskyLens2")
        if self.first is None:
            return ("no face", "white", "n=0")
        r = self.first
        ids = ",".join([str(i) for i in self.ids])
        extra = "n=%d ids=%s x%d y%d" % (self.faces, ids, r["x"], r["y"])
        if self.new_id:
            return ("NEW ID%02d" % self.new_id, id_color(self.new_id), extra[:30])
        return (person_label(r), id_color(r["id"]), extra[:30])

    def summary(self):
        """大きい文字で出す、顔の数と ID の一覧(15字まで)。顔が見えていなければ空。"""
        if self.state != READY or not self.polled or self.miss > 0 or self.faces == 0:
            return ""
        return ("n=%d ids=%s" % (self.faces, ",".join([str(i) for i in self.ids])))[:15]

    def person_color(self):
        """画面の色の帯に使う色の名前。顔がなければ "gray"。"""
        if self.state != READY or self.first is None or self.miss > 0 or not self.polled:
            return "gray"
        if self.new_id:  # 覚えさせた直後(NEW ID)は、新しい ID の色にそろえる
            return id_color(self.new_id)
        return id_color(self.first["id"])

    def photo_short(self):
        """大きい文字で出す、写真の状態(15字まで)。例: PHOTO ON 3 / 失敗したとき PHOTO ON 0!"""
        if self.state == NO_DRIVER:
            return "PHOTO -"
        return "PHOTO %s %d%s" % ("ON" if self.auto_photo else "OFF", self.photos, "!" if self.photo_fail else "")

    def photo_line(self):
        """写真の状態を、画面に出す1行(30字まで)で返す。"""
        if self.state == NO_DRIVER:
            return "PHOTO -"
        mode = "ON" if self.auto_photo else "OFF"
        tail = "FAIL" if self.photo_fail else self.last_photo[-12:]
        return ("PHOTO %s %d %s" % (mode, self.photos, tail))[:30]
