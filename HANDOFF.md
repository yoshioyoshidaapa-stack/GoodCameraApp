# 引き継ぎメモ: ESP32 + HuskyLens2 + Android 時計合わせ（Mac / 初心者向け・日本語）

最終更新: 実機で、顔認識の表示、写真、自動登録、EN 再起動後の復帰まで、動いた段階。

## 相手(ユーザー)について
- コーディングの知識はほぼない前提で、日本語で、手順は1つずつ、コマンドは別のコードブロックで出す。
- わからないことは予想で決めず、質問する。確認できていないことは「未確認」と書く。
- 個人情報(名前、メールアドレス)は、外部に出さない。

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
- 注意: mpremote の `run` は、基板で動いている main.py を止める。プログラムを入れ替えたあとは EN ボタンで再起動。

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
- 切り分けの試験で、赤い線(+)を外しても、EN での症状は変わらなかった。**今の赤い線の接続状態は、未確認(要確認)。**

## 作ったもの(ブランチ `claude/clever-galileo-xyqe6f`)
- `Esp32TimeSync/`
  - `esp32/main.py` : 画面の描画、BOOT ボタン、BLE の時刻受信(時刻は画面には出さない)
  - `esp32/lcd.py` : 画面の部品 (init/clear/rect/text)
  - `esp32/hl_status.py` : HuskyLens2 の状態管理(接続、顔認識、写真、自動登録、バス復旧)
  - `app/` : Android アプリ (Kotlin + Compose, minSdk 31)。CompanionDeviceManager で基板を一度登録すると、近くに来たとき自動で時刻を GATT 書き込み。
    AndroidManifest に `uses-feature android.software.companion_device_setup` が必須。
- `HuskyLens2/`
  - `huskylens2.py` : 公式 Arduino 部品(DFRobot_HuskylensV2 の ProtocolV2.cpp / Result.h)を MicroPython に書き直した I2C 部品
    (knock / switch_algorithm / get_results / take_photo / learn / learn_block / forget)
  - `test_huskylens2.py`(7件) / `test_display.py`(41件) : パソコンの Python で動かす試験(ニセの HuskyLens2・ニセの画面を相手にする)
  - 調査用: `hl_debug.py`(返事の生の数字) / `hl_debug2.py`(結果の返事) / `hl_demo.py` / `hl_line_check.py`(SDA/SCL の状態) /
    `hl_step.py`(命令を1つずつ) / `hl_photo_test.py` / `hl_poll_test.py` / `hl_knock_test.py` / `hl_knock_nowait.py`
  - `docs/wiring.png` / `wiring.svg` : 配線図、`docs/display_mock.png` : 画面の見本(近似)
- 基板に入れるファイル: `lcd.py`, `huskylens2.py`, `hl_status.py`, `main.py`
- 入れ方の例: `python3 -m mpremote connect /dev/cu.usbserial-8340 cp hl_status.py :hl_status.py`

## 画面(240x135、時計は無し)
- y=2: 人の呼び名(大。文字数で 4/3/2 倍): `ID01` / 名前(英数字のみ) / `unknown`(ID 0) / `NEW ID01`(登録直後 5 秒) / `SHOOT`(写真を撮る間)
- y=40: 色の帯(ID ごとの色。0=白,1=赤,2=緑,3=青,4=黄,5=紫,6=水色,7=橙。顔がなければ灰色、撮影中は白)
- y=52: `n=顔の数 ids=全員のID` / y=72: `PHOTO ON 3` / y=92: `ENR ON 2` / y=110: 詳細 / y=122: BLE の状態
- BOOT ボタン: 短押し=写真の入り切り、1.5秒以上=自動登録の入り切り。

## 機能の仕様
- 顔認識の結果は、いちばん大きい顔(いちばん近い人)を代表にする。ID 0 = まだ覚えていない顔。
- 顔が見えたら写真: 10秒に1回まで、電源を入れてから最大50枚、640x480。撮る直前に SHOOT を描く(撮影の間は画面が止まる)。
  保存先は、公式 Wiki によると HuskyLens2 内蔵 USB ドライブの `storage/photo`(マイクロ SD に保存されるかは不明)。
- 自動登録(`learn`): 顔が1つだけ・ID 0・幅 80 以上・画面中央の十字が顔の枠の中、が3回続けて(約3秒)そろったら、覚えさせる。
  間隔10秒以上、電源を入れてから最大8人。実機で「NEW ID01」(赤)が出ることを確認済み。

## 接続の流れ(実機で調整した結果)
1. 起動したら、I2C のバス復旧(SCL を最大9回動かして STOP) → 1秒待つ。
2. 3秒おきに、住所 0x50 へ、16バイトの読み取りで探す(全住所の scan は、やめた)。
3. 見つかったら knock(返事は1.5秒まで待つ)。成功したら **4秒待つ**。
4. 顔認識に切り替え(返事は3秒まで待つ。**返事が届かなくても、すぐには、つなぎ直さない**)。
5. 2秒待ってから、1秒おきに結果を読む。**5回続けて読めなければ**、つなぎ直す。

## 失敗と教訓
- MicroPython には `bytearray.pop()` が無い。パソコンの試験は通っても、実機で AttributeError(knock のところで止まる)。
  → `test_display.py` に、MicroPython に無い書き方を検出する試験を追加。ただし、すべては拾えない。
- 返事が想定と違う、または遅いとき、プログラムが止まる/つなぎ直しを繰り返す → エラー表示、待ち時間、連続失敗の回数で対応。
- **ESP32 を EN で再起動すると、HuskyLens2 がメニューに戻り、すぐ顔認識に切り替えると、映像が出ず(真っ黒)、タッチも効かなくなった。**
  → knock のあとに、4秒待ってから切り替えると、映像が出た。ただし、本当の原因は、確認できていない(待つ対策が効いた、という事実だけ)。
  切り分けの試験: 命令を1つずつ送る(`hl_step.py`)では、固まらなかった。赤い線(+)を外しても、変わらなかった。
- 画面の見本画像を作ると、「NEW ID01」のとき色の帯が白のままの不具合に気づけた。見本は、動かさずに、配置・色を確認できる。
- 商品ページの説明が別の機種のものだった → 実物の写真と配線表を、ユーザーに送ってもらって確認した。
- HuskyLens2 に、顔を覚えさせないと、どの顔も ID 0(白)。覚えさせ方: 顔認識の画面で、十字を枠の中に合わせて、本体右上の Button-A。
- ファームウェア: 1.2.1 → 1.4.2 に更新済み(K230BurningTool、Mac は normal の .dmg、Medium=EMMC、
  `.img.7z` は解凍してから書き込む。解凍前のまま書くと起動しない)。

## BLE 時計合わせの仕様(画面には出さないが、動作は残してある)
- 基板は BLE の周辺機器。名前 `ESP32CLK`。
  サービス UUID `7d1c0001-7a3e-4c55-9f0b-5b2a1d6e0001`、特性 `7d1c0002-7a3e-4c55-9f0b-5b2a1d6e0001`(書き込み)
- 書き込む内容: ASCII `YYYY-MM-DD HH:MM:SS` (スマホの現地時間)。基板の RTC に設定。電源を入れ直すと消える → アプリが自動で再送する(確認済み)。

## 未確認
- 自動登録で、同じ人が、別人として、何度も登録されるか(角度・明るさの違い)。覚えた ID が、電源を入れ直したあとも、残るか。
- HuskyLens2 の信号線(D/T, C/R)が何ボルトか(公式の回答では直接触れられていない)。長時間の動作(熱、電源不足)。
- EN 再起動後の固まりの、本当の原因。ほかの条件(電源の弱さ、写真を撮るタイミング)での再発。
- 写真の保存先が、マイクロ SD かどうか。画面の描き直しにかかる時間。
- 顔認識以外の認識(`ALGO_` 定数は定義済み)。手・姿勢などの、体の点の座標の読み取りは、未実装。

## 環境メモ(クラウド側の作業環境。別の Claude には、関係ないことが多い)
- 通信の許可に追加したホスト: dl.google.com, maven.google.com, plugins.gradle.org, wiki.dfrobot.com, www.dfrobot.com
- Android SDK は、`/root/android-sdk` に入れた(アプリのビルド確認用)。

## 次の候補
1. 顔認識の結果を使った機能(在室・不在の表示、人ごとの記録、など。アイデア50個の一覧は、会話にあった)
2. 他の認識を試す(物体、QR コード、色など)
3. 長時間の安定確認(再発の確認)
4. 覚えた ID の保存(`saveKnowledges`)や、忘れさせる(`forget`)ボタン
