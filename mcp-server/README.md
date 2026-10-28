# GoodCamera MCPサーバー

Claude（claude.ai やスマホの Claude アプリ）に、次の2つの「道具」を持たせる MCP サーバーです。

| 道具 | できること |
|---|---|
| `get_japan_time` | 今の日本時間（日付・曜日・時刻）を答える |
| `get_photo_light_times` | 指定した日・場所の日の出、日の入り、ゴールデンアワー、ブルーアワーの時刻を答える |

Claude に「明日さいたまで夕焼けを撮るなら何時に行けばいい？」のように聞くと、この道具を使って答えます。

## 仕組み

```
スマホのClaudeアプリ → Claude（Anthropic）→ このMCPサーバー（Cloudflare Workers）
```

サーバーは Cloudflare Workers（無料枠あり）に置きます。

## 公開の手順（最初の1回だけ）

1. **Cloudflare のアカウントを作る**: https://dash.cloudflare.com/sign-up
2. **アカウントIDを控える**: Cloudflare ダッシュボードの「Workers & Pages」画面の右側に表示される「Account ID」
3. **APIトークンを作る**: 右上の人型アイコン →「My Profile」→「API Tokens」→「Create Token」→
   テンプレート「Edit Cloudflare Workers」を選んで作成。表示されたトークンを控える（一度しか表示されません）
4. **GitHub に登録する**: このリポジトリの「Settings」→「Secrets and variables」→「Actions」→「New repository secret」で次の2つを登録
   - 名前 `CLOUDFLARE_API_TOKEN` ／ 値：手順3のトークン
   - 名前 `CLOUDFLARE_ACCOUNT_ID` ／ 値：手順2のアカウントID
5. **公開する**: GitHub の「Actions」タブ →「MCPサーバーを公開（Cloudflare Workers）」→「Run workflow」
6. 成功すると、ログの最後に `https://goodcamera-mcp.<あなたのサブドメイン>.workers.dev` のようなURLが出ます

## Claude に登録する

1. パソコンのブラウザで claude.ai を開く
2. 「設定」→「コネクタ」→「カスタムコネクタを追加」
3. URL に `https://goodcamera-mcp.<あなたのサブドメイン>.workers.dev/mcp` を入力（最後の `/mcp` を忘れずに）
4. 追加すると、スマホの Claude アプリでも使えるようになります

## 注意

- このサーバーには鍵（ログイン）をかけていません。URLを知っている人は誰でも呼び出せますが、
  答えるのは時刻と日の出の計算結果だけなので、個人情報が漏れることはありません。
  個人的な情報を扱う道具を追加する場合は、先に鍵をかける仕組みが必要です。
- 時刻の計算は天文計算による目安で、誤差は数分程度です。

## 開発者向け

```bash
npm install
npm test          # テスト
npm run dev       # 手元で起動（http://localhost:8787/mcp）
```
