# パソコン上で、クローラーの「頭の中(drive.py)」を動かして、動きをアニメーションにする。
# 実行: python3 simulate.py   → docs/sim_*.gif ができる
# ※ 速さ・回る速さ・カメラの視野は「仮の数字」。ロジック(いつ回る・止まる・下がる)の確認用で、実機の速さとは違う。
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "Esp32TimeSync", "esp32"))
import drive

FONT = "/usr/share/fonts/opentype/ipafont-gothic/ipag.ttf"

# ---- 仮の数字(実機に合わせて直す) ----
SPEED = 0.10  # 前進・後退の速さ (m/秒)
TURN_RATE = 45.0  # その場で回る速さ (度/秒)
FOV = 70.0  # カメラの横の視野(度)。HuskyLens2 の実際の値は調べていない
BIRD_SIZE = 0.10  # 鳥の大きさ (m)
F_PX = 320 / math.tan(math.radians(FOV / 2))  # 画面 640 px に対する焦点距離
SONAR_MAX = 2.5
DT = 0.1  # 1ステップ (秒)


class FakeHusky:
    def __init__(self):
        self.faces = 0
        self.first = None
        self.holding = False

    def is_ready(self):
        return True


def raycast(x, y, th, room_w, room_h, max_d):
    """向き th(度)に、壁までの距離。部屋は (0,0)-(room_w,room_h)。"""
    dx, dy = math.cos(math.radians(th)), math.sin(math.radians(th))
    best = max_d
    for t in ((0 - x) / dx if dx < -1e-9 else None, (room_w - x) / dx if dx > 1e-9 else None,
              (0 - y) / dy if dy < -1e-9 else None, (room_h - y) / dy if dy > 1e-9 else None):
        if t is not None and 0 < t < best:
            best = t
    return best


def simulate(room, start, bird, seconds, planner_kw=None, stop_when_near=True):
    rw, rh = room
    x, y, th = start
    pl = drive.Planner(**(planner_kw or {}))
    husky = FakeHusky()
    cmd = "stop"
    frames = []
    near_since = None
    for k in range(int(seconds / DT)):
        t = k * DT
        now = int(t * 1000)
        # --- カメラ: 鳥が見えるか ---
        info = None
        if bird is not None:
            bx, by = bird
            dx, dy = bx - x, by - y
            dist = math.hypot(dx, dy)
            rel = (math.degrees(math.atan2(dy, dx)) - th + 180) % 360 - 180
            if abs(rel) < FOV / 2 and dist > 0.02:
                px = 320 - F_PX * math.tan(math.radians(rel))
                w = F_PX * BIRD_SIZE / dist
                info = (px, w, dist)
        if info:
            husky.faces = 1
            husky.first = {"x": int(info[0]), "w": int(info[1])}
        else:
            husky.faces = 0
            husky.first = None
        # --- 距離センサー(正面と、左右15度の、いちばん近い値) ---
        d = min(raycast(x, y, th + a, rw, rh, SONAR_MAX) for a in (-15, 0, 15)) - 0.05
        dist_cm = d * 100 if d < SONAR_MAX - 0.06 else None
        cmd = pl.update(husky, now, cmd, dist_cm)
        frames.append(dict(t=t, x=x, y=y, th=th, cmd=cmd, bird=bird, info=info, dist_cm=dist_cm,
                           scouting=pl.scouting, avoiding=pl.avoiding, phase=pl.phase, avoid_phase=pl.avoid_phase,
                           blocked=pl.blocked))
        # --- 動く ---
        if cmd == "forward":
            nx, ny = x + SPEED * DT * math.cos(math.radians(th)), y + SPEED * DT * math.sin(math.radians(th))
            x, y = min(max(nx, 0.1), rw - 0.1), min(max(ny, 0.1), rh - 0.1)
        elif cmd == "back":
            nx, ny = x - SPEED * DT * math.cos(math.radians(th)), y - SPEED * DT * math.sin(math.radians(th))
            x, y = min(max(nx, 0.1), rw - 0.1), min(max(ny, 0.1), rh - 0.1)
        elif cmd == "left":
            th += TURN_RATE * DT
        elif cmd == "right":
            th -= TURN_RATE * DT
        th %= 360
        if stop_when_near and info and cmd == "stop" and info[1] >= drive.NEAR_W:
            near_since = near_since if near_since is not None else t
            if t - near_since > 2.0:
                break
    return frames


LABEL = {"forward": "前進", "back": "後退", "left": "左に回る", "right": "右に回る", "stop": "停止"}
COLOR = {"forward": (40, 160, 70), "back": (200, 120, 20), "left": (50, 100, 220), "right": (50, 100, 220), "stop": (120, 120, 120)}


def state_text(f):
    if f["avoiding"]:
        return "壁をよけている（下がる→離れる向きに回る）"
    if f["scouting"]:
        if f["phase"] == "turn":
            return "偵察中：回って探す"
        if f["phase"] == "look":
            return "偵察中：止まって見る"
        return "偵察中：前進して探す"
    if f["info"]:
        return "鳥を追いかけている"
    return "鳥を見失った：止まって待つ（3秒）"


def render(frames, room, title, out, every=2, ms=100):
    rw, rh = room
    S = 200  # 1 m = S ピクセル
    W, H = 1000, 560
    ox, oy = 30, 120
    font = ImageFont.truetype(FONT, 18)
    big = ImageFont.truetype(FONT, 26)
    small = ImageFont.truetype(FONT, 14)
    imgs = []
    trail = []
    for i, f in enumerate(frames):
        trail.append((f["x"], f["y"]))
        if i % every:
            continue
        im = Image.new("RGB", (W, H), (246, 249, 252))
        d = ImageDraw.Draw(im)
        d.text((W // 2, 24), title, font=big, fill=(20, 40, 70), anchor="mm")
        d.text((W // 2, 54), "※ 速さ・回る速さ・カメラの視野は仮の数字です。判断のしかた(ロジック)の確認用で、実機の速さとは違います。", font=small, fill=(160, 20, 20), anchor="mm")

        def P(px, py):  # メートル -> 画像
            return ox + px * S, oy + (rh - py) * S

        # 部屋
        d.rectangle([P(0, rh), P(rw, 0)], fill=(255, 255, 255), outline=(60, 60, 60), width=4)
        d.text((ox, oy - 22), "上から見た図（ベランダの壁に囲まれている）", font=small, fill=(60, 80, 100))
        # 軌跡
        pts = [P(a, b) for a, b in trail[::2]]
        if len(pts) > 1:
            d.line(pts, fill=(170, 200, 240), width=3)
        # カメラの視野
        cx, cy = P(f["x"], f["y"])
        a0, a1 = f["th"] - FOV / 2, f["th"] + FOV / 2
        poly = [(cx, cy)]
        for j in range(13):
            a = a0 + (a1 - a0) * j / 12
            r = min(0.9, raycast(f["x"], f["y"], a, rw, rh, 0.9)) * S  # 視野は、壁で止める
            poly.append((cx + r * math.cos(math.radians(a)), cy - r * math.sin(math.radians(a))))
        d.polygon(poly, fill=(255, 240, 190))
        # 距離センサー
        if f["dist_cm"] is not None:
            ex = cx + f["dist_cm"] / 100 * S * math.cos(math.radians(f["th"]))
            ey = cy - f["dist_cm"] / 100 * S * math.sin(math.radians(f["th"]))
            d.line([(cx, cy), (ex, ey)], fill=(200, 60, 60), width=2)
        # 鳥
        if f["bird"]:
            bx, by = P(*f["bird"])
            d.ellipse([bx - 12, by - 8, bx + 12, by + 8], fill=(138, 90, 43), outline=(50, 30, 10))
            d.text((bx, by - 22), "鳥", font=small, fill=(90, 50, 20), anchor="mm")
        # クローラー(長方形)
        L, Wd = 0.22, 0.16
        c, s = math.cos(math.radians(f["th"])), math.sin(math.radians(f["th"]))
        corners = []
        for lx, ly in ((L / 2, Wd / 2), (L / 2, -Wd / 2), (-L / 2, -Wd / 2), (-L / 2, Wd / 2)):
            px = f["x"] + lx * c - ly * s
            py = f["y"] + lx * s + ly * c
            corners.append(P(px, py))
        d.polygon(corners, fill=COLOR[f["cmd"]], outline=(0, 0, 0))
        fx, fy = P(f["x"] + (L / 2) * c, f["y"] + (L / 2) * s)
        d.ellipse([fx - 5, fy - 5, fx + 5, fy + 5], fill=(20, 20, 20))  # 前(カメラ)

        # カメラの画面(右)
        vx, vy, vw, vh = 700, 120, 270, 202
        d.rectangle([vx, vy, vx + vw, vy + vh], fill=(235, 242, 255), outline=(60, 60, 60), width=2)
        d.text((vx, vy - 22), "HuskyLens2 に写る画面", font=small, fill=(60, 80, 100))
        for q in (220, 420):
            xx = vx + q / 640 * vw
            d.line([(xx, vy), (xx, vy + vh)], fill=(150, 190, 230), width=1)
        if f["info"]:
            px, w, dist = f["info"]
            bx = vx + px / 640 * vw
            bw = max(w / 640 * vw, 6)
            by = vy + vh / 2
            d.rectangle([bx - bw / 2, by - bw / 2, bx + bw / 2, by + bw / 2], outline=(230, 190, 0), width=3)
            d.text((bx, by), "鳥", font=small, fill=(90, 50, 20), anchor="mm")
            d.text((vx + vw / 2, vy + vh + 14), "x=%d  幅=%d" % (px, w), font=small, fill=(60, 60, 60), anchor="mm")
        else:
            d.text((vx + vw / 2, vy + vh / 2), "（鳥は見えない）", font=small, fill=(130, 130, 150), anchor="mm")
        d.text((vx, vy + vh + 30), "細い線の内側 = 「中央」（220〜420）", font=small, fill=(100, 120, 150))

        # 状態
        d.rectangle([vx, 380, vx + vw, 460], fill=(255, 255, 255), outline=COLOR[f["cmd"]], width=3)
        d.text((vx + vw / 2, 402), "動き：" + LABEL[f["cmd"]], font=font, fill=COLOR[f["cmd"]], anchor="mm")
        d.text((vx + vw / 2, 436), "前の距離：" + ("%dcm" % f["dist_cm"] if f["dist_cm"] is not None else "遠い"), font=font, fill=(60, 60, 60), anchor="mm")
        d.text((ox, oy + rh * S + 24), "状態：" + state_text(f), font=font, fill=(20, 40, 70))
        d.text((W - 30, H - 24), "時間 %4.1f 秒（表示は2倍速）" % f["t"], font=font, fill=(60, 60, 60), anchor="rm")
        imgs.append(im)
    imgs[0].save(out, save_all=True, append_images=imgs[1:], duration=ms, loop=0, optimize=True)
    return len(imgs)


if __name__ == "__main__":
    docs = os.path.join(HERE, "docs")
    # 場面A: 鳥が後ろ(右奥)にいる。見失った状態から、回って探して、見つけて、近づく
    fa = simulate((3.0, 1.6), (0.5, 0.8, 180.0), (2.5, 1.3), 90)
    print("A frames", len(fa), "最後の時間", fa[-1]["t"])
    print("A", render(fa, (3.0, 1.6), "場面A：鳥を探して、見つけて、近づく", os.path.join(docs, "sim_scout.gif")), "枚")
    # 場面B: 鳥がいない。偵察の前進で壁に近づく → よける
    fb = simulate((3.0, 1.6), (1.6, 0.8, 0.0), None, 70, planner_kw=dict(steps=2, forward_ms=14000), stop_when_near=False)
    print("B", render(fb, (3.0, 1.6), "場面B：鳥がいない。偵察で前進して、壁をよける", os.path.join(docs, "sim_avoid.gif")), "枚")
