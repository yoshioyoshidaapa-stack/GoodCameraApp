# HUSKYLENS 2 簡易原寸モデル（外形 70 x 58 x 19 mm は公式の数値）
# 画面くぼみ・レンズ位置などの細部は「推定」です。
import trimesh, numpy as np
from trimesh.creation import box, cylinder

W, H, D = 70.0, 58.0, 19.0   # 横, 縦, 厚み (mm)
R = 3.0                       # 角の丸み(推定)

# 本体：角を丸めた板
body = cylinder(radius=R, height=D, sections=48)
body.apply_translation([W/2 - R, H/2 - R, 0])
parts = []
for sx in (-1, 1):
    for sy in (-1, 1):
        c = cylinder(radius=R, height=D, sections=48)
        c.apply_translation([sx*(W/2-R), sy*(H/2-R), 0])
        parts.append(c)
parts.append(box([W-2*R, H, D]))
parts.append(box([W, H-2*R, D]))
body = trimesh.boolean.union(parts, engine="manifold")

# 前面（上面 z=+D/2）：2.4インチ 4:3 の画面くぼみ 48.8 x 36.6, 深さ0.6mm
screen = box([48.8, 36.6, 1.2]); screen.apply_translation([0, 2, D/2])
body = trimesh.boolean.difference([body, screen], engine="manifold")

# 背面（下面 z=-D/2）：レンズ用の丸いくぼみ 直径14, 深さ3mm（位置は推定）
lens = cylinder(radius=7, height=6, sections=48); lens.apply_translation([0, 0, -D/2])
body = trimesh.boolean.difference([body, lens], engine="manifold")

# 原点を底面に合わせる
body.apply_translation([0, 0, D/2])
body.export("huskylens2_simple.stl")
print("watertight:", body.is_watertight, "size(mm):", body.extents.round(2), "volume(mm3):", round(body.volume,1))
