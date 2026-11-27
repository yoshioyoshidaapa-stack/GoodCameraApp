# 3Dプリント用の部品モデル(STL)を作るプログラム。 実行: python3 make_parts.py
# 単位はすべて mm。 数字を直して、もう一度実行すると、STL が作り直される。
import numpy as np
import trimesh


def box(x, y, z, cx=0, cy=0, z0=0):
    m = trimesh.creation.box(extents=(x, y, z))
    m.apply_translation((cx, cy, z0 + z / 2))
    return m


def cyl(d, h, cx=0, cy=0, z0=0, sections=64):
    m = trimesh.creation.cylinder(radius=d / 2, height=h, sections=sections)
    m.apply_translation((cx, cy, z0 + h / 2))
    return m


def union(parts):
    return trimesh.boolean.union(parts, engine="manifold")


def diff(a, b):
    return trimesh.boolean.difference([a, b], engine="manifold")


# ---- SG90 サーボ(連続回転タイプ)の見本 --------------------------------------------
# ★ 一般に公開されている SG90 の寸法(22.2 x 11.8 x 31 mm など)をもとにした見本。
# ★ 「SG90-HV」の仕様ページは読めていないので、実物を測って、下の数字を直すこと。
SG90 = dict(
    body_l=22.2,  # 本体の長さ
    body_w=11.8,  # 本体の幅
    body_h=22.7,  # 本体の高さ(底から、ケースの上まで)
    ear_len=32.2,  # 耳(固定用の出っ張り)を含む全長
    ear_t=2.5,  # 耳の厚み
    ear_z=15.9,  # 底から、耳の下の面までの高さ
    ear_hole_d=2.0,  # 耳の穴の直径
    ear_hole_gap=27.8,  # 耳の穴と穴の間隔
    bump_d=11.8,  # 軸の根元の、丸い出っ張りの直径
    bump_h=3.8,  # その高さ(本体の上から)
    bump_off=5.9,  # 軸の中心の、本体の端からの距離
    spline_d=4.8,  # 軸の直径
    total_h=31.0,  # 軸の先までの全高
)


def make_sg90(p=SG90):
    L, W, H = p["body_l"], p["body_w"], p["body_h"]
    body = box(L, W, H, 0, 0, 0)
    ear = box(p["ear_len"], W, p["ear_t"], 0, 0, p["ear_z"])
    # 耳の穴(貫通)
    holes = [cyl(p["ear_hole_d"], p["ear_t"] + 2, cx=s * p["ear_hole_gap"] / 2, cy=0, z0=p["ear_z"] - 1)
             for s in (-1, 1)]
    ear = diff(ear, union(holes))
    x_shaft = -L / 2 + p["bump_off"]  # 軸は、片方の端に寄っている
    bump = cyl(p["bump_d"], p["bump_h"], cx=x_shaft, z0=H - 0.01)
    spline = cyl(p["spline_d"], p["total_h"] - (H + p["bump_h"]) + 0.02, cx=x_shaft, z0=H + p["bump_h"] - 0.01)
    m = union([body, ear, bump, spline])
    m.apply_translation(-m.bounds[0])
    return m


# ---- 電解コンデンサ(16V 1000µF の例: 直径10 x 高さ17 mm、足2本) --------------------
def make_capacitor(d=10.0, h=17.0, lead_d=0.6, lead_len=8.0, pitch=5.0):
    body = cyl(d, h, z0=0)
    legs = [cyl(lead_d, lead_len, cx=s * pitch / 2, z0=-lead_len + 0.01, sections=16) for s in (-1, 1)]
    m = union([body] + legs)
    m.apply_translation(-m.bounds[0])
    return m


if __name__ == "__main__":
    for name, m in (("sg90_servo_approx", make_sg90()), ("capacitor_16V1000uF_approx", make_capacitor())):
        m.export(name + ".stl")
        print(name, [round(float(v), 2) for v in m.extents], "watertight", m.is_watertight)
