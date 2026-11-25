# 引き継ぎメモ: ESP32 + HuskyLens2 + Android 時計合わせ（Mac / 初心者向け・日本語）

## 相手(ユーザー)について
- コーディングの知識はほぼない前提で、日本語で、手順は1つずつ、コマンドは別のコードブロックで出す。
- わからないことは予想で決めず、質問する。確認できていないことは「未確認」と書く。

## ハードウェア
- ESP32 基板: ideaspark ESP32 1.14インチ LCD (印字 V4.1 / USB-C / ESP-WROOM-32 / ESP32-D0WD rev1.0 / USB-シリアルは CH340)
  - Mac から見える名前: `/dev/cu.usbserial-8340`
  - 商品ページの説明に「0.96インチ OLED SSD1306 (SDA=21, SCL=22)」が混ざっていたが、実物は別物(LCD)。I2C で画面を探して時間を失った。
- 画面: ST7789 系 240x135、SPI。MOSI=23, SCLK=18, CS=15, DC=2, RST=4, BLK=32。向き MADCTL=0xA0、位置のずれ x=40, y=53。
  初期化時に画面メモリ全体(240x320)を黒で塗る(白い線が残るのを防ぐ)。
- HuskyLens2: DFRobot SEN0638 + HUSKYLENS 2 Power Board V1.0.0 + 付属ケーブル2本(白い4ピン-4ピン、色付き4ピン-メス4本)
- Android: HEADWOLF Titan 1 (タブレット, Android 16)

## ソフト環境
- 基板: MicroPython v1.29.0 (ESP32_GENERIC 20260824)。Mac から `python3 -m esptool` / `python3 -m mpremote`(pip --user で導入)。
- 書き込み: `erase_flash` → `write_flash -z 0x1000 <bin>`
- 注意: mpremote の `run` は、基板で動いている main.py を止める。EN ボタンで再起動。

## 作ったもの(ブランチ `claude/clever-galileo-xyqe6f`)
- `Esp32TimeSync/`
  - `esp32/main.py` : BLE で時刻を受け取り、画面に時計 + HuskyLens2 の状態 + BLE 状態を出す
  - `esp32/lcd.py` : 画面の部品 (init/clear/rect/text)
  - `esp32/hl_status.py` : HuskyLens2 の状態管理 (NOT_FOUND→FOUND→READY)。どんなエラーでも止まらず、画面にエラーを出す
  - `app/` : Android アプリ (Kotlin + Compose, minSdk 31)。CompanionDeviceManager で基板を一度登録すると、
    近くに来たとき自動で GATT 書き込み。手動送信ボタンもある。AndroidManifest に
    `uses-feature android.software.companion_device_setup` が必須(無いと登録時に IllegalStateException)。
- `HuskyLens2/`
  - `huskylens2.py` : 公式 Arduino 部品(DFRobot_HuskylensV2 の ProtocolV2.cpp / Result.h)を MicroPython に書き直した I2C 部品
  - `hl_demo.py` / `hl_debug.py` : 試験・原因調べ用
  - `test_huskylens2.py` / `test_display.py` : パソコンの Python で動かす試験(ニセの機械を相手にする)
  - `docs/wiring.png` / `docs/wiring.svg` : 配線図
- 基板に入れるファイル: `lcd.py`, `huskylens2.py`, `hl_status.py`, `main.py`

## BLE 時計合わせの仕様
- 基板は BLE の周辺機器。名前 `ESP32CLK`。
  サービス UUID `7d1c0001-7a3e-4c55-9f0b-5b2a1d6e0001`、特性 `7d1c0002-7a3e-4c55-9f0b-5b2a1d6e0001`(書き込み)
- 書き込む内容: ASCII `YYYY-MM-DD HH:MM:SS` (スマホの現地時間)。基板の RTC に設定。
- 電池がないので、電源を入れ直すと時刻は消える → アプリが自動で再送する(確認済み)。

## HuskyLens2 の要点
- ファームウェアを 1.2.1 → 1.4.2 に更新済み(公式の Arduino 部品は 1.4.2 以上が必要)。
  - K230BurningTool (Mac 版は normal の .dmg)。Medium は EMMC(SEN0638)。
  - 圧縮ファイル `.img.7z` を、そのまま書き込むと起動しなくなる。必ず解凍した `.img` を書き込む。
  - 書き込みは Button-A を押しながら電源を入れる。HuskyLens2 は USB で Mac に直接つなぐ。
- 通信: I2C(工場出荷)、住所 0x50、100kHz。Protocol Type が I2C か、表示灯が青かで確認。
- 公式の電源の考え方: 電源ボードの PWR IN に USB-C で給電する(コントローラの端子からは足りない)。

## 配線(実機で動いた)
- HuskyLens2 ⇄(白い4ピン)⇄ 電源ボード I2C/UART 端子
- 電源ボード PWR IN ← USB-C 電源
- 電源ボード Gravity 端子 ⇄(色付き)⇄ ESP32:

  | 色 | 電源ボード | ESP32 |
  |---|---|---|
  | 緑 | D/T (SDA) | D21 |
  | 青 | C/R (SCL) | D22 |
  | 黒 | − (GND) | GND |
  | 赤 | + (入力) | 3V3 |

  赤は上の列の 3V3。下の列の VIN(5V)ではない。
- 電源を入れる順番: 先に電源ボードの USB 電源 → HuskyLens2 の起動を待つ → そのあと ESP32 の USB。
- 根拠: DFRobot フォーラム https://www.dfrobot.com/forum/topic/403800
  (Gravity の + は電源ボードの入力で一方向。I2C では D/T=SDA, C/R=SCL)

## 実機で確認できたこと
- 画面に時計と日付と曜日が出る。BLE で時刻が自動で合う(電源を入れ直しても)。
- ESP32 から HuskyLens2 が I2C で見つかり、画面に状態が出て、動いた(ユーザー報告「動いた」。画面に出た細かい状態は未記録)。

## 失敗と教訓
- MicroPython には `bytearray.pop()` が無い。パソコンの Python の試験は通っても、実機で AttributeError になった。
  → `test_display.py` に、MicroPython に無い書き方を検出する試験を追加済み。ただし、すべては拾えない。
- 返事が想定と違う形のとき、プログラムが止まる → `hl_status.py` で、エラーを画面に出す作りに変更。
- 商品ページの説明が別の機種のものだった → 実物の写真と配線表を、ユーザーに送ってもらって確認した。

## 未確認
- HuskyLens2 の信号線(D/T, C/R)が何ボルトか。公式の回答では直接触れられていない。
- 長時間の動作(熱、電源不足、再起動)。
- 顔認識以外の認識(`ALGO_` 定数は定義済み)、学習(learn)は未実装。
- BLE の自動起動は、Android の電池の節約設定などで止められる可能性がある。

## 次の候補
1. 顔認識の結果を使う(顔が見えたら色を変える等)
2. 他の認識を試す
3. 長時間の安定確認
4. 学習の命令を追加
