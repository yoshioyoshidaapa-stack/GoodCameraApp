# HuskyLens 2 (DFRobot SEN0638) を ESP32 の MicroPython から使う

`huskylens2.py` は、DFRobot 公式の Arduino 用部品
(https://github.com/DFRobot/DFRobot_HuskylensV2) の通信の決まりを読んで、
MicroPython に書き直した小さな部品です。

## 状態

- **実機で動作を確認しました**（利用者の報告：「動いた」。画面に出た細かい状態は、記録していません）。
- 確認した組み合わせ：ESP32（ESP-WROOM-32、ideaspark 1.14インチ LCD 基板）+ HUSKYLENS 2（ファームウェア 1.4.2）+ HUSKYLENS 2 Power Board V1.0.0。
- 実機で見つかった不具合：MicroPython に無い `bytearray.pop()` を使っていて、`knock` の段階で AttributeError になりました。直して、再発防止の試験（`test_display.py` の `test_no_micropython_incompatible_calls`）を追加しました。
- `test_huskylens2.py` と `test_display.py` は、パソコンの Python で動かす試験です。MicroPython との違いは、すべては拾えません。

## 配線（実機で動いた形）

詳しくは `docs/wiring.png`（`docs/wiring.svg`）を見てください。

| 色付きケーブル | 電源ボード Gravity 端子 | ESP32 基板 |
|---|---|---|
| 緑 | D/T（SDA） | D21 |
| 青 | C/R（SCL） | D22 |
| 黒 | −（GND） | GND |
| 赤 | +（入力） | 3V3 |

- HuskyLens2 は、白い4ピンケーブルで、電源ボードの「I2C/UART」端子につなぐ。
- 電源ボードの「PWR IN」に、USB-C で電源を入れる。
- 電源を入れる順番：先に電源ボードの USB 電源 → HuskyLens2 の起動を待つ → そのあと ESP32 の USB。
- 根拠：DFRobot フォーラムの回答（Gravity の「+」は、電源ボードにとっての入力で、一方向。D/T = SDA、C/R = SCL）。
  https://www.dfrobot.com/forum/topic/403800

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

## まだ確認していないこと

- 信号線（D/T、C/R）が、何ボルトでつり上げられているか。公式の回答では、直接は触れられていません。実機は、動いています。
- ESP32 の 3V3 と、PWR IN の電源を、両方つないだ状態での、長時間の動作（熱、電源の不足）。

## 試験(パソコンで)

```bash
python3 test_huskylens2.py
```
