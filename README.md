# DopaGuard

AndroidのYouTube利用中に自動分析し、ゴールから離れた可能性をオーバーレイで示し、本人が選んだ場合に解除可能な休止を行うMVP。
現行要件は [要件定義v0.3](docs/requirements/REQUIREMENTS_v0.3.md)、新規開発はDG-300〜308。実機の1分周期の自動Agent分析と状態アイコンまで接続済み。休止提案・本人承認休止・解除は開発中。
開発指示・要件の正本は [docs/README.md](docs/README.md)。

現在の状態は [最新引き継ぎ](docs/process/HANDOFF_2026-10-12_CURRENT.md) を最初に読む。別端末で再開する場合は [再開・引き継ぎ資料](docs/process/RESUME_ON_ANOTHER_MACHINE.md) も参照する。実機テストは再開済み。今後エミュレータは使用しない。

2026-10-11のスコープ変更: WebデモはMVP対象外。v0.3では利用中の自動監視→分析→提案→本人承認→休止/解除（RA-01〜09）を完成条件とする。AC-01〜06は既存基盤の証拠として保持する。

以下の旧プロトタイプ/過去検証の記述は履歴。現在の権限・実機・配置・受入状態は最新引き継ぎを優先する。

## Android観測プロトタイプ（DG-001〜007）

- Android SDK 36、minSdk 26、targetSdk 36、JDK 21（Android Studio同梱JBR）。
- Windows: `cd app` → `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease`。
- Debug APK: `app/app/build/outputs/apk/debug/app-debug.apk`。
- Release APKは未署名。配布審査・実機収集の成功を意味しない。
- 以下の端末操作はユーザーが実機試験を再開した後のみ行う。現在はインストール・実機収集を保留する。
- 再開後、USBデバッグを有効にした本人の端末に `adb install -r <APK>` でインストールする。
- 起動画面で収集説明を確認し同意、その後OSのAccessibility設定からサービスを手動で有効化する。
- YouTubeのホーム、検索結果（検索入力自体は対象外）、動画詳細、Shortsを操作し、取得状態を確認する。
- 機密値を避けた公開動画で検証する。原文と画面の照合は本人が行う。
- タイムラインはアプリ専用Room DB `guard.db`。同期・分析・訂正コードは実装済み、実機E2Eは未確認。実観測は`data_origin=real`、架空データは`sample`。
- 初回に困りごとを選び、本人がゴールを確認する。端末内収集とクラウド同意は別々に選択する。
- UsageStatsの開始・終了とAccessibilityクリックを統合。権限がなければ開始・終了は捏造しない。
- プロセス消失などで終了が観測できなければ「終了未確定」。SCROLLは500ms単位で重複抑止。
- 端末の保持期間は7日。収集・同期の保守処理で期限切れデータと孤立キューを削除する。クラウド内容の保持期限は未整備。
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

## 現在の分析構成と制約

Android → Firebase本人認証付きCloud Run → Agent Engine上のADK → Vertex Geminiの構成。Toolは本人のFirestoreデータを読み、分析は観測・根拠に対する仮説として表示する。本人の明示回答だけを訂正APIへ保存し、元の観測は書き換えない。[アーキテクチャ](docs/architecture/OVERVIEW.md) を参照。

- projectはdopadopa、公開APIはhttps://dopaguard-api-463106527119.asia-northeast1.run.app。
- 公開Engine経路の架空分析・観測不足unknown・認可・訂正・削除は検証済み。証拠はartifacts/cloud/cloud-api-smoke.jsonとcloud-api-smoke-unobservable.json。
- 最後のAndroid/Engine連携試験はタイムアウトで失敗。Android待機上限を65秒へ修正し単体18件/APKビルド成功、端末上の解消確認は未実施。Engine transportの断続的失敗原因も未特定。
- 実YouTube文言取得、実機E2E、提出動画・提出条件確認は未完了。MVP完成や後悔・依存の判定成功は主張しない。
- 最新受入判定は [MVP受入状況](docs/process/MVP_ACCEPTANCE_STATUS.md)、別端末でのセットアップは [再開資料](docs/process/RESUME_ON_ANOTHER_MACHINE.md)。
