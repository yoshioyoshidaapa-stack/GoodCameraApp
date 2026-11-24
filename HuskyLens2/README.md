# HuskyLens 2 (DFRobot SEN0638) を ESP32 の MicroPython から使う

`huskylens2.py` は、DFRobot 公式の Arduino 用部品
(https://github.com/DFRobot/DFRobot_HuskylensV2) の通信の決まりを読んで、
MicroPython に書き直した小さな部品です。

## 状態

- **本物の HuskyLens2 では、まだ動かしていません。**
- `test_huskylens2.py` は、公式コードの読み取りに基づく「ニセの機械」相手の試験です。
  部品自身が、自分の想定と合っているかを確かめるだけで、本物の機械の応答が合っている保証にはなりません。
- 配線(どの線をどの穴につなぐか)と電源の条件は、**まだ公式の資料で確認していません**。確認するまで、つながないでください。

## 分かっていること

### 公式の Wiki で確認したこと (https://wiki.dfrobot.com/sen0638/)

- 動作電圧は 3.3〜5V、消費電力は 1.5〜3W。
- 通信は、工場出荷の状態で I2C。住所は `0x50`。
  UART で使うときは、HuskyLens2 の画面の「システム設定」→「Protocol Type」で切り替える。
- 通信の方式は、UART/I2C の端子のそばの表示灯で見分けられる。I2C は青、UART は緑。
- 配線は、4ピンのジャンパーケーブル（またはデュポン線）を使う。
- **電源に関する注意（公式の Arduino の説明より）**: Arduino Uno の I2C 端子の出力電圧では、
  HuskyLens2 を動かすには足りない。HuskyLens2 の USB-C 端子に、別に USB-C ケーブルをつないで電源を入れる。
  電源ボード（HUSKYLENS 2 Power Board）を使う方法も、公式が勧めている。

### 公式の Arduino 用部品(コード)より

- I2C の住所は `0x50`、速度は 100kHz。
- 公式の部品が対応するファームウェアは 1.4.2 以上。
- 公式の部品は UART でも使えるが、この部品は I2C のみ。

## まだ確認できていないこと

- 4ピンのケーブルの、**どの線がどの端子か**（SDA、SCL、電源、GND の割り当て）。公式の Wiki では、配線図の画像に書かれていて、文章では読めていません。
- この ESP32 基板(ESP-WROOM-32)の I2C の穴は、SDA が GPIO21、SCL が GPIO22 の予定。
- 本物の HuskyLens2 での動作確認。

## 試験(パソコンで)

```bash
python3 test_huskylens2.py
```
