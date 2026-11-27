W,H=1200,900
o=[]
def add(s): o.append(s)
def line(pts,col,w=3,dash=None):
    d=" ".join(("M" if i==0 else "L")+"%d %d"%p for i,p in enumerate(pts))
    add('<path d="%s" fill="none" stroke="%s" stroke-width="%d" stroke-linejoin="round"%s/>'%(d,col,w,(' stroke-dasharray="%s"'%dash) if dash else ""))
def vgap(x,y0,y1,col,gaps,w=3):
    """縦線。gaps の位置(他の線と交差するところ)は、途切れさせる(つながっていない印)。"""
    ys=[y0]
    segs=[]
    cur=y0
    for g in sorted(gaps):
        segs.append((cur,g-7)); cur=g+7
    segs.append((cur,y1))
    for a,b in segs:
        if b>a: add('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="%s" stroke-width="%d"/>'%(x,a,x,b,col,w))
def dot(x,y,col="#111"): add('<circle cx="%d" cy="%d" r="5" fill="%s"/>'%(x,y,col))
def pin(x,y,col): add('<circle cx="%d" cy="%d" r="7" fill="%s" stroke="#000"/>'%(x,y,col))
def box(x,y,w,h,fill,stroke,title,sub=None,tc="#123"):
    add('<rect x="%d" y="%d" width="%d" height="%d" rx="10" fill="%s" stroke="%s" stroke-width="2"/>'%(x,y,w,h,fill,stroke))
    add('<text x="%d" y="%d" font-size="17" font-weight="bold" text-anchor="middle" fill="%s">%s</text>'%(x+w/2,y+26,tc,title))
    if sub: add('<text x="%d" y="%d" font-size="12" text-anchor="middle" fill="#444">%s</text>'%(x+w/2,y+44,sub))
def text(x,y,s,size=13,col="#111",anchor="start",bold=False):
    add('<text x="%d" y="%d" font-size="%d" fill="%s" text-anchor="%s"%s>%s</text>'%(x,y,size,col,anchor,' font-weight="bold"' if bold else "",s))
def res(x,y,w,h,label,lx,ly):
    add('<rect x="%d" y="%d" width="%d" height="%d" fill="#fff" stroke="#111" stroke-width="2"/>'%(x,y,w,h))
    text(lx,ly,label,12,"#111","middle")

RED="#d11"; BLK="#222"; USB="#7a3fb0"; ORG="#f08000"; GRN="#1aa83c"; BLU="#1e5fe0"; OLV="#7a9a20"

# wires first
# USB cables
line([(230,190),(300,190),(300,130),(430,130)],USB,4)
line([(230,270),(270,270),(270,490),(130,490),(130,530)],USB,4)
line([(230,350),(330,350),(330,615),(430,615)],USB,4)
# I2C
line([(430,170),(375,170),(375,590),(430,590)],GRN)
line([(430,220),(390,220),(390,565),(430,565)],BLU)
line([(430,270),(405,270),(405,540),(430,540)],BLK)
# servo signals
line([(650,170),(740,170),(740,150),(800,150)],ORG)
line([(650,220),(720,220),(720,310),(800,310)],ORG)
# sonar signals
line([(650,270),(700,270),(700,500),(820,500)],OLV)
line([(650,320),(670,320),(670,550),(820,550)],OLV)
# rails
line([(160,700),(1100,700)],RED,4)
line([(100,730),(1130,730)],BLK,4)
line([(160,640),(160,700)],RED,4)
line([(100,640),(100,730)],BLK,4)
# power buses
vgap(1100,170,700,RED,[200,360],3)
line([(1060,170),(1100,170)],RED); line([(1060,330),(1100,330)],RED)
vgap(1130,200,730,BLK,[],3)
line([(1060,200),(1130,200)],BLK); line([(1060,360),(1130,360)],BLK)
# sonar power
line([(940,620),(940,700)],RED)
vgap(1010,620,730,BLK,[700],3)
# esp gnd and divider R2
vgap(660,370,730,BLK,[700],3); line([(650,370),(660,370)],BLK)
vgap(740,620,730,BLK,[700],3); line([(740,550),(740,580)],OLV)
# capacitor
line([(880,700),(880,710)],RED); line([(880,720),(880,730)],BLK)
add('<line x1="862" y1="710" x2="898" y2="710" stroke="#111" stroke-width="4"/><line x1="862" y1="720" x2="898" y2="720" stroke="#111" stroke-width="4"/>')
# junction dots
for x,y,c in [(160,700,RED),(940,700,RED),(1100,700,RED),(1100,170,RED),(1100,330,RED),(880,700,RED),
              (100,730,BLK),(660,730,BLK),(740,730,BLK),(1010,730,BLK),(1130,730,BLK),(880,730,BLK),(1130,200,BLK),(1130,360,BLK),(740,550,OLV)]:
    dot(x,y,c)

# boxes
box(30,110,200,330,"#fff4d6","#a60","モバイルバッテリー","出力 1A 以上 × 3口",  "#520")
for y,lab in ((190,"口① 基板用"),(270,"口② サーボ・センサー用"),(350,"口③ HuskyLens2用")):
    add('<rect x="222" y="%d" width="16" height="22" fill="#ddd" stroke="#333"/>'%(y-11)); text(40,y+5,lab,14)
box(430,110,220,330,"#eef3ff","#355","ESP32 基板","(ideaspark 液晶つき)")
text(352,124,"USB-C ← 口①",11,USB)
for y,col,lab in ((170,GRN,"D21 (SDA)"),(220,BLU,"D22 (SCL)"),(270,"#222","GND")):
    pin(430,y,col); text(444,y+5,lab,14,"#111",bold=True)
for y,col,lab in ((170,ORG,"GPIO25"),(220,ORG,"GPIO26"),(270,OLV,"GPIO27"),(320,OLV,"GPIO33"),(370,"#222","GND")):
    pin(650,y,col); text(636,y+5,lab,14,"#111","end",True)
box(430,486,220,150,"#eaf7ea","#363","HuskyLens2 電源ボード","PWR IN の USB-C に給電(口③)",  "#131")
for y,lab in ((540,"黒 GND"),(565,"青 SCL"),(590,"緑 SDA")): text(440,y+4,lab,12); 
text(440,616,"USB-C ← 口③",11,USB)
text(540,654,"赤線はつなぐ必要なし(前の図と同じ)",11,"#252","middle")
box(30,530,200,110,"#f3e9ff","#639","USB 端子台","口②の USB から取り出す","#306")
text(160,632,"+5V",13,RED,"end",True); text(100,632,"GND",13,"#111","end",True)
text(140,606,"口② USB ← ",12,USB,"middle")
box(800,110,260,130,"#fff0f0","#a33","左サーボ (SG90-HV)",None,"#511")
text(812,155,"橙 信号 ← GPIO25",13,"#a50"); text(812,175,"赤 (+) → +5V",13,RED); text(812,195,"茶 (−) → GND",13)
box(800,270,260,130,"#fff0f0","#a33","右サーボ (SG90-HV)",None,"#511")
text(812,315,"橙 信号 ← GPIO26",13,"#a50"); text(812,335,"赤 (+) → +5V",13,RED); text(812,355,"茶 (−) → GND",13)
box(820,450,250,170,"#fffbe0","#a80","前方の距離センサー",None,"#540"); text(945,588,"HC-SR04 など。車体の前に1個",12,"#540","middle")
text(832,505,"TRIG ← GPIO27",13); text(832,555,"ECHO → 分圧 → GPIO33",13)
text(940,612,"VCC",12,RED,"end"); text(1030,612,"GND",12,"#111","end")
# divider labels
res(760,543,40,14,"",0,0); text(780,538,"1kΩ",12,"#111","middle"); 
add('<rect x="733" y="580" width="14" height="40" fill="#fff" stroke="#111" stroke-width="2"/>'); text(722,604,"2kΩ",12,"#111","end")
text(752,574,"分圧点",11,"#573","start")
text(880,752,"↑ コンデンサ 470〜1000µF（+と−の向きに注意。+側を赤い線に）",12,"#a00","middle")
text(170,690,"+5V（赤）",13,RED,"start",True); text(110,755,"GND（黒）",13,"#111","start",True)
# titles
text(600,36,"クローラー 最新版 配線図",26,"#123","middle",True)
text(600,60,"設計図です。実機では未確認。つなぐときは、必ずバッテリーを外した状態で。線の色は、図の中でだけ意味があります。",13,"#a00","middle")
# legend
text(30,790,"線の見方：",14,"#111",bold=True)
for i,(c,lab) in enumerate(((USB,"USBケーブル"),(RED,"+5V（赤）"),(BLK,"GND（黒）"),(ORG,"サーボの信号"),(OLV,"距離センサーの信号"),(GRN,"I2C SDA"),(BLU,"I2C SCL"))):
    x=(120,250,370,490,625,830,950)[i]
    line([(x,785),(x+28,785)],c,5); text(x+34,790,lab,13)
text(30,825,"・線が交差していて、片方が途切れている所は「つながっていない」です。丸い点があるところだけが、つながっています。",13)
text(30,848,"・ECHO は 5V を出します。ESP32 には 3.3V までなので、必ず 1kΩ と 2kΩ の抵抗で下げてから GPIO33 へ（3.3V 対応のセンサーなら不要）。",13,"#a00")
text(30,871,"・サーボの電気（口②）と、基板・HuskyLens2 の電気（口①③）は、別の USB 口から取る。ESP32 の GND と端子台の GND は、必ずつなぐ。",13)
svg='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" font-family="Noto Sans CJK JP, WenQuanYi Zen Hei, Hiragino Sans, Yu Gothic, sans-serif"><rect width="%d" height="%d" fill="#ffffff"/>%s</svg>'%(W,H,W,H,W,H,"\n".join(o))
open("/home/user/GoodCameraApp/HuskyLens2/docs/crawler_wiring_latest.svg","w",encoding="utf-8").write(svg)
