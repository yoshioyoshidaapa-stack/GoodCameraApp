# HUSKYLENS 2 詳細モデル（背面の部品位置は DFRobot 公式の図から計測した値）
# 座標：背面から見て 左端=X0 / 上端=Y0（下向きが +Y）。単位 mm。
# ・外形 70x58x19 は公式値。 ・背面の部品位置は図から計測（誤差 約±1mm）。
# ・厚み方向の寸法、下面ポートの位置、TFスロット位置は「推定」。
import trimesh, numpy as np
from trimesh.creation import box, cylinder
from trimesh.boolean import union, difference

BODY_W, BODY_H = 70.0, 51.0     # 本体（突起タブを除く）
TAB_H = 7.0                     # 下のタブ（合計 51+7=58）
T = 13.5                        # 本体の厚み（推定）。レンズ込みで合計19mmにしてある
CAM_HOUSING, CAM_BARREL = 1.5, 4.0   # 背面にカメラが出る高さ（推定）

def B(x0, y0, x1, y1, z0, z1):
    """背面図の座標(X,Y下向き)の四角を、モデル座標の箱にする"""
    b = box([x1-x0, y1-y0, z1-z0])
    b.apply_translation([(x0+x1)/2-35, 25.5-(y0+y1)/2, (z0+z1)/2])
    return b
def C(x, y, d, z0, z1, n=40):
    c = cylinder(radius=d/2, height=z1-z0, sections=n)
    c.apply_translation([x-35, 25.5-y, (z0+z1)/2]); return c
def RR(x0,y0,x1,y1,r,z0,z1):
    w,h=x1-x0,y1-y0
    parts=[B(x0+r,y0,x1-r,y1,z0,z1),B(x0,y0+r,x1,y1-r,z0,z1)]
    for cx in (x0+r,x1-r):
        for cy in (y0+r,y1-r): parts.append(C(cx,cy,2*r,z0,z1))
    return union(parts,engine="manifold")

# ---- 本体
solid = [RR(0,0,BODY_W,BODY_H,3.0,0,T)]
# タブ（基板の出っ張り）：厚み2mm、本体厚みの中央あたり（推定）
zc = T/2
solid.append(B(23.75,BODY_H-1,46.25,BODY_H+TAB_H,zc-1,zc+1))
# カメラ：土台＋ツバ（左右の耳）＋レンズ筒
solid.append(B(26.6,1.9,43.75,18.1,T,T+CAM_HOUSING))
solid.append(RR(22.5,6.9,47.75,13.75,3.0,T,T+CAM_HOUSING))
solid.append(C(35.0,9.4,7.7,T+CAM_HOUSING-0.1,T+CAM_HOUSING+CAM_BARREL))
# ボタンA（出っ張り）
solid.append(B(15.5-4.9,5.5-3.25,15.5+4.9,5.5+3.25,T,T+1.0))
body = union(solid, engine="manifold")

cuts = []
# ネジ穴（本体固定）
for x,y in [(5.9,5.25),(64.6,5.25),(5.9,45.9),(64.6,45.9)]:
    cuts.append(C(x,y,3.5,T-3,T+0.1))
# カメラ固定ネジ穴
for x in (26.25,44.1): cuts.append(C(x,10.0,2.5,T,T+CAM_HOUSING+0.1))
# レンズ穴
cuts.append(C(35.0,9.4,3.0,T+CAM_HOUSING+CAM_BARREL-2.5,T+CAM_HOUSING+CAM_BARREL+0.1))
# RGBライト・マイク・フィルライト（浅いくぼみ）
cuts.append(B(56.6-1.25,5.25-1.25,56.6+1.25,5.25+1.25,T-0.5,T+0.1))
cuts.append(C(52.25,5.25,1.2,T-1.0,T+0.1))
for x in (5.4,63.75): cuts.append(B(x-3,12.25-1.4,x+3,12.25+1.4,T-0.5,T+0.1))
# WiFiアンテナカバー（浅い段差）
cuts.append(B(0.5,19.75,7.1,40.6,T-0.4,T+0.1))
# スピーカー格子（浅い溝）
for k in range(14):
    y = 19.5 + k*1.5
    cuts.append(B(8.6,y,19.5,y+0.7,T-0.6,T+0.1))
    cuts.append(B(48.8,y,67.0,y+0.7,T-0.6,T+0.1))
# タブの穴
for x in (28.75,41.6): cuts.append(C(x,BODY_H+3.75,3.2,zc-2,zc+2))
# 下面ポート（Gravity 4ピン、USB-C）：位置は推定
cuts.append(B(13-6.9,BODY_H-5,13+6.9,BODY_H+0.1,zc-3.0,zc+3.0))
cuts.append(B(57-4.45,BODY_H-5,57+4.45,BODY_H+0.1,zc-1.65,zc+1.65))
# TFカードスロット（右側面）：位置は推定
cuts.append(B(BODY_W-5,40-5.5,BODY_W+0.1,40+5.5,zc-0.8,zc+0.8))
# 前面の画面くぼみ
cuts.append(B(35-24.4,25.5-18.3+25.5-25.5,35+24.4,25.5+18.3,-0.1,0.6)) if False else None
scr = box([48.8,36.6,0.7]); scr.apply_translation([0,0,0.25]); cuts.append(scr)

body = difference([body]+[c for c in cuts if c is not None], engine="manifold")
body.export("huskylens2_detailed.stl")
print("watertight:", body.is_watertight, "extents(mm):", body.extents.round(2), "volume:", round(body.volume,1))
