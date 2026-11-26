# HuskyLens 2 (DFRobot SEN0638) を ESP32 の MicroPython から I2C で使う小さな部品
#
# 通信の決まりは、DFRobot 公式の Arduino 用部品
#   https://github.com/DFRobot/DFRobot_HuskylensV2 (ProtocolV2.cpp / Result.h)
# を読んで、そのまま MicroPython に書き直したもの。
# ※ 実機ではまだ動かしていない。公式の部品が対応するファームウェアは 1.4.2 以上。
#
# 使い方の例:
#   from machine import I2C, Pin
#   import huskylens2 as hl
#   i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=100000)
#   cam = hl.HuskyLens2(i2c)
#   if cam.knock():
#       cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION)
#       for r in cam.get_results(hl.ALGO_FACE_RECOGNITION):
#           print(r)

import struct

try:
    from time import ticks_ms, ticks_diff, sleep_ms
except ImportError:  # パソコンの Python で試験するとき
    import time

    def ticks_ms():
        return int(time.monotonic() * 1000)

    def ticks_diff(a, b):
        return a - b

    def sleep_ms(ms):
        time.sleep(ms / 1000)


I2C_ADDR = 0x50  # 公式の部品の addr
LCD_WIDTH = 640  # カメラ画面の大きさ（公式の部品の値）
LCD_HEIGHT = 480

# コマンド
CMD_KNOCK = 0x00
CMD_GET_RESULT = 0x01
CMD_SET_ALGORITHM = 0x0A
CMD_ACTION_TAKE_PHOTO = 0x20
CMD_ACTION_LEARN = 0x22
CMD_ACTION_FORGET = 0x23
CMD_ACTION_LEARN_BLOCK = 0x2C
CMD_RETURN_ARGS = 0x1A
CMD_RETURN_INFO = 0x1B
CMD_RETURN_BLOCK = 0x1C
CMD_RETURN_ARROW = 0x1D

# 認識の種類（アルゴリズム）
ALGO_ANY = 0
ALGO_FACE_RECOGNITION = 1
ALGO_OBJECT_RECOGNITION = 2
ALGO_OBJECT_TRACKING = 3
ALGO_COLOR_RECOGNITION = 4
ALGO_OBJECT_CLASSIFICATION = 5
ALGO_SELF_LEARNING_CLASSIFICATION = 6
ALGO_SEGMENT = 7
ALGO_HAND_RECOGNITION = 8
ALGO_POSE_RECOGNITION = 9
ALGO_LICENSE_RECOGNITION = 10
ALGO_OCR_RECOGNITION = 11
ALGO_LINE_TRACKING = 12
ALGO_EMOTION_RECOGNITION = 13
ALGO_GAZE_RECOGNITION = 14
ALGO_FACE_ORIENTATION = 15
ALGO_TAG_RECOGNITION = 16
ALGO_BARCODE_RECOGNITION = 17
ALGO_QRCODE_RECOGNITION = 18
ALGO_FALLDOWN_RECOGNITION = 19

# 写真の解像度
RESOLUTION_DEFAULT = 0
RESOLUTION_640x480 = 1
RESOLUTION_1280x720 = 2
RESOLUTION_1920x1080 = 3

_FRAME_SIZE = 512  # 受信できる1つの枠の最大サイズ（公式の部品の値）
_MAX_RESULT = 10  # 一度に受け取る結果の最大数（公式の部品の値）
_WRITE_CHUNK = 64  # I2C の1回で送る最大バイト数（公式の部品の値）
_BOARD_LARGE_RAM = 1  # ESP32 は「大きいメモリの基板」として名乗る


class HuskyLens2:
    def __init__(self, i2c, addr=I2C_ADDR, timeout_ms=5000, retry=5):
        self.i2c = i2c
        self.addr = addr
        self.timeout_ms = timeout_ms
        self.retry = retry
        self.max_id = 0
        self._rx = bytearray(_FRAME_SIZE)
        self._idx = 0
        self._pending = b""  # 読み取り済みで、まだ解析していない分
        self._pos = 0  # _pending の、どこまで解析したか

    # ---- 送る側 ----
    @staticmethod
    def build_packet(cmd, algo=0, data=b""):
        """55 AA コマンド アルゴ 長さ データ… 合計(下位8ビット)"""
        body = bytes([0x55, 0xAA, cmd, algo, len(data)]) + bytes(data)
        return body + bytes([sum(body) & 0xFF])

    def _write(self, packet):
        n = len(packet)
        full = n // _WRITE_CHUNK
        remain = n % _WRITE_CHUNK
        for i in range(full):
            self.i2c.writeto(self.addr, packet[i * _WRITE_CHUNK:(i + 1) * _WRITE_CHUNK])
            sleep_ms(5)
        if remain:
            self.i2c.writeto(self.addr, packet[full * _WRITE_CHUNK:])

    # ---- 受ける側 ----
    def _feed(self, b):
        """1バイトずつ受け取り、1つの枠が完成して合計も合えば、その枠を返す。"""
        i = self._idx
        rx = self._rx
        if i == 0:
            if b != 0x55:
                return None
            rx[0] = b
        elif i == 1:
            if b != 0xAA:
                self._idx = 0
                return None
            rx[1] = b
        elif i == 4:
            if b + 6 > _FRAME_SIZE:
                self._idx = 0
                return None
            rx[4] = b
        elif i < 4:
            rx[i] = b
        else:
            rx[i] = b
            if i == rx[4] + 5:  # 合計のバイトまで届いた
                self._idx = 0
                end = rx[4] + 5
                if (sum(rx[:end]) & 0xFF) == rx[end]:
                    return bytes(rx[:end + 1])
                return None
        self._idx = i + 1
        return None

    def _read_frame(self, deadline):
        # ※ MicroPython の bytearray には pop() がないので、位置(_pos)を進める方式にしている
        while ticks_diff(deadline, ticks_ms()) > 0:
            if self._pos >= len(self._pending):
                try:
                    self._pending = bytes(self.i2c.readfrom(self.addr, 16))
                    self._pos = 0
                except OSError:
                    sleep_ms(2)
                    continue
            while self._pos < len(self._pending):
                b = self._pending[self._pos]
                self._pos += 1
                frame = self._feed(b)
                if frame is not None:
                    return frame
        return None

    def _wait(self, command):
        deadline = ticks_ms() + self.timeout_ms
        while ticks_diff(deadline, ticks_ms()) > 0:
            frame = self._read_frame(deadline)
            if frame is None:
                return None
            if frame[2] == command:
                return frame
            # 違う種類の枠は読み飛ばす（公式の部品と同じ）
        return None

    # ---- 命令 ----
    def knock(self, timeout_ms=None):
        """つながっているかの確認。成功すると True。timeout_ms で、返事を待つ時間を、指定できる。"""
        packet = self.build_packet(CMD_KNOCK, ALGO_ANY, bytes([_BOARD_LARGE_RAM]) + bytes(13))
        return self._ack(packet, timeout_ms, pause_ms=10)

    def switch_algorithm(self, algo, timeout_ms=None):
        """認識の種類を切り替える。成功すると True。
        切り替えは、モデルの読み込みで、時間がかかることがある。timeout_ms で、返事を待つ時間を、長くできる。"""
        packet = self.build_packet(CMD_SET_ALGORITHM, ALGO_ANY, bytes([algo]) + bytes(13))
        return self._ack(packet, timeout_ms)

    def _ack(self, packet, timeout_ms, pause_ms=0):
        """命令を送って、「返事の戻り値が 0」なら True。返事がなければ False。"""
        old = self.timeout_ms
        if timeout_ms is not None:
            self.timeout_ms = timeout_ms
        try:
            for _ in range(self.retry):
                self._write(packet)
                if pause_ms:
                    sleep_ms(pause_ms)
                frame = self._wait(CMD_RETURN_ARGS)
                if frame is not None:
                    return _int8(frame[6]) == 0
        finally:
            self.timeout_ms = old
        return False

    def learn(self, algo, timeout_ms=None):
        """いま画面の中央の十字の下にある顔を覚えさせる(本体の Button-A と同じ)。
        付いた ID (1, 2, 3 …) を返す。失敗したら 0。
        ※ 本物の HuskyLens2 では、まだ試していない。"""
        return self._id_reply(self.build_packet(CMD_ACTION_LEARN, algo, b""), timeout_ms)

    def learn_block(self, algo, x, y, w, h, timeout_ms=None):
        """指定した位置・大きさの枠の中のものを覚えさせる。付いた ID を返す。失敗したら 0。
        ※ x, y が「枠の中心」か「左上」かは、公式のコードから読み取れていない。"""
        data = bytes([0, 0]) + struct.pack("<hhhh", x, y, w, h) + bytes(4)
        return self._id_reply(self.build_packet(CMD_ACTION_LEARN_BLOCK, algo, data), timeout_ms)

    def forget(self, algo, timeout_ms=None):
        """覚えた ID を、すべて忘れさせる。成功すると True。"""
        packet = self.build_packet(CMD_ACTION_FORGET, algo, b"")
        old = self.timeout_ms
        if timeout_ms is not None:
            self.timeout_ms = timeout_ms
        try:
            for _ in range(self.retry):
                self._write(packet)
                frame = self._wait(CMD_RETURN_ARGS)
                if frame is not None:
                    return _int8(frame[6]) == 0
        finally:
            self.timeout_ms = old
        return False

    def _id_reply(self, packet, timeout_ms):
        old = self.timeout_ms
        if timeout_ms is not None:
            self.timeout_ms = timeout_ms
        try:
            for _ in range(self.retry):
                self._write(packet)
                frame = self._wait(CMD_RETURN_ARGS)
                if frame is not None:
                    data = frame[5:-1]
                    if len(data) < 4:
                        return 0
                    return struct.unpack_from("<h", data, 2)[0] & 0xFF  # 公式のコードも uint8 に切る
        finally:
            self.timeout_ms = old
        return 0

    def take_photo(self, resolution=RESOLUTION_1280x720, timeout_ms=None):
        """写真を撮らせる。HuskyLens2 が保存したファイル名を返す。失敗したら空の文字列 ""。
        ※ 保存先は、公式 Wiki によると HuskyLens2 内蔵の USB ドライブの storage/photo。
        ※ 本物の HuskyLens2 では、まだ試していない。"""
        packet = self.build_packet(CMD_ACTION_TAKE_PHOTO, ALGO_ANY, bytes([resolution]) + bytes(13))
        old = self.timeout_ms
        if timeout_ms is not None:
            self.timeout_ms = timeout_ms
        try:
            for _ in range(self.retry):
                self._write(packet)
                frame = self._wait(CMD_RETURN_ARGS)
                if frame is not None:
                    data = frame[5:-1]
                    if len(data) < 2 or _int8(data[1]) != 0:
                        return ""
                    return _read_string(data, 14)
        finally:
            self.timeout_ms = old
        return ""

    def get_results(self, algo):
        """認識の結果を、辞書の一覧で返す。失敗したら None。"""
        packet = self.build_packet(CMD_GET_RESULT, algo, b"")
        info = None
        for _ in range(self.retry):
            self._write(packet)
            info = self._wait(CMD_RETURN_INFO)
            if info is not None:
                break
        if info is None:
            return None
        data = info[5:-1]
        self.max_id = data[0]
        total_results, _learned, total_blocks, _blocks_learned = struct.unpack_from("<hhhh", data, 2)
        total_results = min(total_results, _MAX_RESULT)
        total_blocks = min(total_blocks, _MAX_RESULT)
        results = []
        for _ in range(total_blocks):
            frame = self._wait(CMD_RETURN_BLOCK)
            if frame is not None:
                results.append(parse_result(frame))
        for _ in range(total_blocks, total_results):
            frame = self._wait(CMD_RETURN_ARROW)
            if frame is not None:
                results.append(parse_result(frame))
        return results


def _int8(v):
    return v - 256 if v > 127 else v


def parse_result(frame):
    """1つの結果の枠 (BLOCK / ARROW) を辞書にする。"""
    data = frame[5:-1]
    r = {
        "type": "block" if frame[2] == CMD_RETURN_BLOCK else "arrow",
        "id": data[0],
        "level": _int8(data[1]),
        "x": 0,
        "y": 0,
        "w": 0,
        "h": 0,
        "conf": 0.0,
        "name": "",
        "content": "",
    }
    if len(data) >= 14:
        r["x"], r["y"], r["w"], r["h"] = struct.unpack_from("<hhhh", data, 2)
        r["conf"] = struct.unpack_from("<f", data, 10)[0]
    header = 14
    n = len(data)
    if n > header:
        name_len = data[header]
        if header + 2 + name_len <= n:
            r["name"] = _text(data[header + 1:header + 1 + name_len])
            content_len = data[header + 1 + name_len]
            if header + 2 + name_len + content_len <= n:
                start = header + 2 + name_len
                r["content"] = _text(data[start:start + content_len])
    return r


def _read_string(data, off):
    """data の off 番目から「長さ 1バイト + 文字」の文字列を読む。足りなければ空。"""
    if len(data) <= off:
        return ""
    n = data[off]
    if off + 1 + n > len(data):
        return ""
    return _text(data[off + 1:off + 1 + n])


def _text(b):
    try:
        return bytes(b).decode("utf-8")
    except Exception:
        return ""
