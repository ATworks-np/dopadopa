# DopaGuard

AndroidのYouTube操作を観測し、Geminiが根拠イベント付きの行動仮説を提示するP0 MVP。
開発指示・要件の正本は [docs/README.md](docs/README.md)。

## Android観測プロトタイプ（DG-001〜007）

- Android SDK 36、minSdk 26、targetSdk 36、JDK 21（Android Studio同梱JBR）。
- Windows: `cd app` → `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease`。
- Debug APK: `app/app/build/outputs/apk/debug/app-debug.apk`。
- Release APKは未署名。配布審査・実機収集の成功を意味しない。
- USBデバッグを有効にした本人の端末に `adb install -r <APK>` でインストールする。
- 起動画面で収集説明を確認し同意、その後OSのAccessibility設定からサービスを手動で有効化する。
- YouTubeのホーム、検索結果（検索入力自体は対象外）、動画詳細、Shortsを操作し、取得状態を確認する。
- 機密値を避けた公開動画で検証する。原文と画面の照合は本人が行う。
- タイムラインはアプリ専用Room DB `guard.db`。Android同期コードは実装中。`data_origin=real`。
- 初回に困りごとを選び、本人がゴールを確認する。端末内収集とクラウド同意は別々に選択する。
- UsageStatsの開始・終了とAccessibilityクリックを統合。権限がなければ開始・終了は捏造しない。
- プロセス消失などで終了が観測できなければ「終了未確定」。SCROLLは500ms単位で重複抑止。
- 保持期間は7日。収集処理時に期限切れデータを削除する。
- 「収集とクラウド同意を撤回」で新規取得を停止。「端末内データをすべて削除」で観測・分析・訂正・ゴール・同意を削除。
- 旧スパイクのJSONLは正式DBに移行・送信しない。削除操作で旧ファイルも削除する。
- 親ノードの文言抽出は実機照合前のため無効。欠損を推測で埋めない。

## 検証

`python -m unittest discover -s docs/scripts -p 'test_*.py'`

`python docs/scripts/tickets.py validate`

現時点の実機試験とクラウド接続の状態は `docs/process/HANDOFF.md` を参照。

クラウドAPIとGemini runnerの設定・ローカルテストは [server/README.md](server/README.md)。

## Androidクラウド同期（DG-008、検証中）

- Firebaseのメール/パスワードでDopaGuardへログインする。Google Cloudの管理アカウントとは別。
- クラウド同意後の新しい同意revisionで収集した終了済みセッションだけを、同期ボタンから送る。未同意時の過去イベントは送信しない。
- Firebase ID tokenとTLSを使用。ログにtoken/パスワード/イベント原文を出さず、パスワードは画面のメモリだけで扱う。
- 同期キューの再送待ち/送信済み状態はアプリ専用領域に保存。失敗は上限付き待機で再試行、収集とは別スレッドで処理する。
- ログアウト/撤回は新規送信を停止し実行中通信のキャンセルを試みる。すでに届いたデータはクラウド削除ボタンで削除する。削除失敗時は完了と表示しない。
- ローカルデータの所有者は最初の同期アカウントに固定。別アカウントに無断で送信しない。
- CloudSyncIntegrationTestで実Android認証/sample同期/未同意拒否/撤回/削除を検証済み。実YouTube収集の成功ではない。証拠artifacts/tests/android-cloud-sync.xml。
