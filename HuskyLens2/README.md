# HuskyLens 2 (DFRobot SEN0638) を ESP32 の MicroPython から使う

`huskylens2.py` は、DFRobot 公式の Arduino 用部品
(https://github.com/DFRobot/DFRobot_HuskylensV2) の通信の決まりを読んで、
MicroPython に書き直した小さな部品です。

## 状態

- **本物の HuskyLens2 では、まだ動かしていません。**
- `test_huskylens2.py` は、公式コードの読み取りに基づく「ニセの機械」相手の試験です。
  部品自身が、自分の想定と合っているかを確かめるだけで、本物の機械の応答が合っている保証にはなりません。
- 配線(どの線をどの穴につなぐか)と電源の条件は、**まだ公式の資料で確認していません**。確認するまで、つながないでください。

## 分かっていること (公式コードより)

- I2C の住所は `0x50`、速度は 100kHz。
- 公式の部品が対応するファームウェアは 1.4.2 以上。
- 公式の部品は UART でも使えるが、この部品は I2C のみ。

## 試験(パソコンで)

```bash
python3 test_huskylens2.py
```
