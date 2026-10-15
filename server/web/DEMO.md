# 動画デモ

## 公開

Firebase Hosting: https://dopadopa.web.app/demo

`firebase.json` はHostingから `dopaguard-api`（asia-northeast1）へ転送する。分析には60秒以上かかる場合があるため、公開デモのAPI通信はCloud Runへ直接接続し、Hostingの60秒制限を避ける。CORSはこのプロジェクトの `.web.app` / `.firebaseapp.com` の2つだけを許可し、Firebase本人認証を維持する。

Cloud Runへ最新コンテナを反映した後、`firebase deploy --only hosting --project dopadopa` でHosting設定を公開する。既存Firebase CLI認証が期限切れの場合は、ADCが使える別の `XDG_CONFIG_HOME` を指定して実行可能。認証情報はリポジトリへ保存しない。

## ローカル

プロジェクトのルートで起動:

```powershell
.\.venv\Scripts\python.exe -m uvicorn server.app:app --host 127.0.0.1 --port 8080 --no-access-log
```

http://127.0.0.1:8080/demo を開く。

- 最初に5つの利用目的から1つ以上をチェックして「保存する」。AndroidのGoalSelectionと同じ文言・青紫の配色・縦並び・複数選択に対応。画面上部で変更可能。
- 上に動画プレイヤー、下に勉強・音楽・ミーム・ゲーム・暮らしの5ジャンル各5本、合計25本の動画カード。ジャンルで絞り込み可能。
- カードを選ぶとタイトルと再生素材が切り替わり、プレイヤーへスクロールする。
- ジャンル・タイトルは架空。映像はGoogle公開サンプル7種類を共用し、YouTubeとは接続していない。
- 最初に既存Firebaseのメール・パスワードでログイン。トークンはメモリのみで保持する。再読み込み・ログアウトで破棄し、期限切れは再ログインする。
- 右側の同意チェック後の動画選択を最大100件記録。「エージェントで分析」で `/v1/user/generation` → `/v1/sessions:batch` → `/v1/analyses` を呼ぶ。既存の実エージェントが目的と操作内容を調査し、結果とTool記録を表示する。
- データは `data_origin=sample`、`app_id=dopaguard-web-demo`。架空タイトルの操作であり、実YouTubeの視聴証拠にはしない。初期表示はWINDOW_CHANGE、本人のカード選択はCLICKと遷移後のWINDOW_CHANGEを記録する。ユーザー操作によるページ・ジャンル欄のスクロールはSCROLLとして500ms間隔で記録し、タイトルは付けない。動画選択後の自動スクロールは除外。右側で件数と最新12件を確認できる。送信は分析ボタン押下時のみ。
- 同意解除・ログアウト・目的変更は進行中の処理を取消し、古い結果を表示しない。既に送信されたデータはクラウドに残る。既存の `/` から履歴確認・削除可能。
- ローカルから実エージェントを呼ぶ場合は、起動前に `server/cloudrun.engine.env.yaml` と同じ環境変数を設定し、Google CloudのApplication Default Credentialsを用意する。認証情報をフロントエンドに埋め込まない。Firestore・Firebase Admin・Agent Engineへの権限が必要。
- 動画配信とFirebaseログインにはインターネット接続が必要。

確認手順: ログイン → 目的選択 → 保存 → ページを下にスクロール → 別の動画選択 → タイトル・映像切替 → ジャンル絞込 → すべて → 目的変更。狭い画面でも確認する。ログインは本人が行う。
