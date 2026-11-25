# パソコンの Python で動かす試験:  python3 test_huskylens2.py
# 本物の HuskyLens2 の代わりに、公式の部品が読み書きする形（ProtocolV2.cpp / Result.h）を
# まねた「ニセの機械」を相手にして、huskylens2.py が正しく話せるかを確かめる。
# ※ 本物の機械の応答そのものは確認していない（公式コードの読み取りに基づく想定）。

import struct
import huskylens2 as hl


def frame(cmd, algo, data):
    return hl.HuskyLens2.build_packet(cmd, algo, data)


def result_data(id_, x, y, w, h, conf, name="", content=""):
    d = struct.pack("<Bbhhhhf", id_, 0, x, y, w, h, conf)
    if name or content:
        d += bytes([len(name)]) + name.encode() + bytes([len(content)]) + content.encode()
    return d


class FakeI2C:
    """writeto で受けた命令に、決まった応答の枠を用意し、readfrom で16バイトずつ返す。"""

    def __init__(self, drop_first_n=0):
        self.rx = bytearray()
        self.out = bytearray()
        self.chunks = []
        self.drop = drop_first_n  # 最初の何回かの命令を無視する（再送の試験用）
        self.faces_present = True  # False なら、結果が 0 件
        self.photo_fail = False  # True なら、写真は失敗の返事
        self.photo_requests = 0  # 写真を頼まれた回数

    def writeto(self, addr, buf):
        assert addr == 0x50
        assert len(buf) <= 64
        self.chunks.append(len(buf))
        self.rx += buf
        # 1つの枠が完成したら処理する
        while len(self.rx) >= 6:
            size = self.rx[4]
            total = size + 6
            if len(self.rx) < total:
                return
            pkt = bytes(self.rx[:total])
            del self.rx[:total]
            assert pkt[0] == 0x55 and pkt[1] == 0xAA
            assert (sum(pkt[:-1]) & 0xFF) == pkt[-1], "送った合計が合っていない"
            self._respond(pkt)

    def _respond(self, pkt):
        if self.drop > 0:
            self.drop -= 1
            return
        cmd, algo, size = pkt[2], pkt[3], pkt[4]
        data = pkt[5:-1]
        if cmd == hl.CMD_KNOCK:
            assert size == 14 and data[0] == 1, "knock は 14 バイトで、最初が 1"
            self.out += frame(hl.CMD_RETURN_ARGS, 0, bytes([0, 0]) + bytes(12))
        elif cmd == hl.CMD_SET_ALGORITHM:
            assert size == 14
            self.algo = data[0]
            self.out += frame(hl.CMD_RETURN_ARGS, 0, bytes([self.algo, 0]) + bytes(12))
        elif cmd == hl.CMD_ACTION_TAKE_PHOTO:
            assert size == 14 and data[0] in (0, 1, 2, 3), (size, data[0])
            self.photo_requests += 1
            if self.photo_fail:
                self.out += frame(hl.CMD_RETURN_ARGS, 0, bytes([0, 1]) + bytes(12))
            else:
                name = ("IMG_%04d.jpg" % self.photo_requests).encode()
                self.out += frame(hl.CMD_RETURN_ARGS, 0, bytes([0, 0]) + bytes(12) + bytes([len(name)]) + name)
        elif cmd == hl.CMD_GET_RESULT and not self.faces_present:
            info = struct.pack("<BbhhhhI", 0, 0, 0, 0, 0, 0, 0)[:14]
            self.out += frame(hl.CMD_RETURN_INFO, algo, info)
        elif cmd == hl.CMD_GET_RESULT:
            assert size == 0
            # 先に、関係ない種類の枠を1つ混ぜる（読み飛ばされるはず）
            self.out += frame(hl.CMD_RETURN_ARGS, 0, bytes(14))
            info = struct.pack("<BbhhhhI", 7, 0, 3, 3, 2, 2, 0)[:14]
            self.out += frame(hl.CMD_RETURN_INFO, algo, info)
            self.out += frame(hl.CMD_RETURN_BLOCK, algo, result_data(1, 320, 240, 100, 80, 0.5, "Taro", "face"))
            self.out += frame(hl.CMD_RETURN_BLOCK, algo, result_data(2, -5, 10, 20, 30, 0.25))
            self.out += frame(hl.CMD_RETURN_ARROW, algo, result_data(3, 1, 2, 3, 4, 0.0, "", ""))
        else:
            raise AssertionError("知らない命令 %r" % cmd)

    def readfrom(self, addr, n):
        assert addr == 0x50 and n == 16
        chunk = bytes(self.out[:n])
        del self.out[:n]
        return chunk + bytes(n - len(chunk))  # 足りない分は 0 で埋める


def test_checksum_and_packet():
    p = hl.HuskyLens2.build_packet(0x01, 1, b"")
    assert p == bytes([0x55, 0xAA, 0x01, 0x01, 0x00, (0x55 + 0xAA + 1 + 1) & 0xFF])


def test_knock_and_switch():
    cam = hl.HuskyLens2(FakeI2C(), timeout_ms=500)
    assert cam.knock() is True
    assert cam.switch_algorithm(hl.ALGO_FACE_RECOGNITION) is True


def test_get_results():
    cam = hl.HuskyLens2(FakeI2C(), timeout_ms=500)
    rs = cam.get_results(hl.ALGO_FACE_RECOGNITION)
    assert rs is not None and len(rs) == 3, rs
    assert cam.max_id == 7
    a, b, c = rs
    assert (a["type"], a["id"], a["x"], a["y"], a["w"], a["h"]) == ("block", 1, 320, 240, 100, 80)
    assert abs(a["conf"] - 0.5) < 1e-6 and a["name"] == "Taro" and a["content"] == "face"
    assert (b["id"], b["x"], b["y"], b["w"], b["h"], b["name"]) == (2, -5, 10, 20, 30, "")
    assert c["type"] == "arrow" and c["id"] == 3


def test_retry_when_first_commands_are_ignored():
    cam = hl.HuskyLens2(FakeI2C(drop_first_n=2), timeout_ms=100, retry=5)
    assert cam.knock() is True


def test_no_answer_returns_false_or_none():
    cam = hl.HuskyLens2(FakeI2C(drop_first_n=99), timeout_ms=30, retry=2)
    assert cam.knock() is False
    assert cam.get_results(1) is None


def test_bad_checksum_is_ignored():
    cam = hl.HuskyLens2(FakeI2C(), timeout_ms=60, retry=1)
    bad = bytearray(frame(hl.CMD_RETURN_ARGS, 0, bytes(14)))
    bad[-1] ^= 0xFF
    cam.i2c.out += bad
    # 壊れた枠は捨てられ、次に来る本物の枠で成功する
    assert cam.knock() is True


def test_write_is_chunked_by_64():
    class Rec:
        def __init__(self):
            self.chunks = []

        def writeto(self, addr, buf):
            self.chunks.append(len(buf))

    rec = Rec()
    cam = hl.HuskyLens2(rec, timeout_ms=100)
    cam._write(bytes(150))
    assert rec.chunks == [64, 64, 22], rec.chunks


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        t()
        print("OK ", t.__name__)
    print("すべて成功 (%d 件)" % len(tests))
