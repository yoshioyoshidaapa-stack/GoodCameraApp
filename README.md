# GoodCamera

Androidタブレットのカメラ性能を最大限に引き出すカメラアプリ。
ソフトウェアベースの高度な画像処理アルゴリズムで、タブレットのカメラで撮影した写真をプロ品質に近づけます。

## 機能

### 撮影モード

| モード | 説明 |
|--------|------|
| **Auto** | 自動露出・自動フォーカスで手軽に撮影 |
| **Pro** | ISO、シャッタースピード、WB、フォーカスを手動制御 |
| **HDR** | 複数露出のブラケット撮影でダイナミックレンジを拡大 |
| **Night** | マルチフレームスタッキングで暗所ノイズを低減 |

### 画像処理パイプライン

撮影画像に以下の後処理を自動適用（すべてON/OFF可能）:

1. **自動ホワイトバランス補正** - Gray World / White Patch / Shades of Gray の3アルゴリズム統合
2. **ノイズリダクション** - エッジ保持バイラテラルフィルタ（弱/中/強）
3. **ブレ/ピンぼけ自動補正** - ラプラシアン分散によるブラー検出 + 多スケールRichardson-Lucy復元
4. **自動レベル補正** - ヒストグラムベースのコントラスト最適化
5. **知覚補正** - CLAHE局所コントラスト、対数トーンマッピング、記憶色ブースト
6. **シャープニング** - Unsharp Mask（強度調整可能）

### カメラ機能

- **ピンチズーム / スライダーズーム** - デジタルズーム制御
- **タップフォーカス** - タッチした位置に自動フォーカス
- **セルフタイマー** - 3秒 / 5秒 / 10秒
- **グリッドオーバーレイ** - 三分割法 / 黄金比 / クロスヘア
- **フロント/リアカメラ切替**
- **RAW+JPEG 出力** (対応デバイスのみ)

### その他

- **ギャラリー** - 撮影した写真の一覧表示・詳細表示・削除
- **共有** - 撮影画像やレビュー画面から直接共有
- **撮影後レビュー** - 元画像と処理済み画像の比較、後処理パラメータのリアルタイム調整
- **設定画面** - グリッド/タイマー/自動コントラスト等の設定管理

## 技術スタック

- **言語**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **カメラ**: Camera2 API
- **アーキテクチャ**: MVVM (ViewModel + StateFlow)
- **最小SDK**: API 26 (Android 8.0)
- **ターゲットSDK**: API 34

## ビルド

```bash
./gradlew assembleDebug
```

## テスト

```bash
./gradlew testDebugUnitTest
```

## プロジェクト構造

```
app/src/main/java/com/goodcamera/app/
├── MainActivity.kt              # エントリーポイント・画面ナビゲーション
├── camera/
│   ├── CameraController.kt     # Camera2 API制御
│   └── CameraState.kt          # 状態データクラス・列挙型
├── processing/                  # 画像処理パイプライン
│   ├── ImageProcessor.kt       # パイプラインオーケストレーター
│   ├── WhiteBalanceCorrector.kt # 自動ホワイトバランス
│   ├── NoiseReduction.kt       # バイラテラルフィルタ
│   ├── DeblurFilter.kt         # ブレ/ピンぼけ補正
│   ├── AutoLevels.kt           # 自動レベル補正
│   ├── PerceptualEnhancer.kt   # 知覚補正エンジン
│   ├── Sharpening.kt           # Unsharp Mask
│   └── HdrToneMapper.kt        # HDRトーンマッピング
├── ui/
│   ├── components/              # 再利用可能UIコンポーネント
│   │   ├── CaptureButton.kt
│   │   ├── ModeSelectorBar.kt
│   │   ├── ProControlsPanel.kt
│   │   ├── FormatSelector.kt
│   │   └── GridOverlay.kt
│   ├── screens/
│   │   ├── CameraScreen.kt     # メインカメラ画面
│   │   ├── ReviewScreen.kt     # 撮影後レビュー
│   │   ├── GalleryScreen.kt    # ギャラリー
│   │   └── SettingsScreen.kt   # 設定
│   ├── theme/Theme.kt
│   └── viewmodel/CameraViewModel.kt
└── util/ShutterSpeedFormatter.kt
```

## ライセンス

All rights reserved.
