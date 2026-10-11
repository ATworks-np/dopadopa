# 2026-10-11時点の引き継ぎ（履歴）

2026-10-12以降の入口は [HANDOFF_2026-10-12_CURRENT.md](HANDOFF_2026-10-12_CURRENT.md)。このファイルは10-11時点の記録であり、入口にしない。旧HANDOFFの「実機保留」「自動実行未接続」も履歴である。

記録時点: **2026-10-11 19:15 JST**。再開後の実機更新は **19:27 JST** まで確認した。

## 再開後（2026-10-11 19:27 JST）

最新ローカルAPK `ed277c113b6c8b38c6a9d4eff027015f1ea381490b09314e564a0a36313dbc28` を、同じserialのPixel 9aへ `install -r` 済み。インストール後の実機APKハッシュも同一。Accessibilityは有効のまま、YouTube前面でオーバーレイが戻った。

- バッジ「Agent · 待機中」「Agent · 判定中」はどちらも1行。判定中の文言は実request中の「直近の観測とゴールを分析しています。」と同時に出た。
- 再接続後の1分周期は継続。開始14→18、終了14→18。完了のたびに次周期へ進み、失敗直後の連続再送にはなっていない。
- 状態を取りこぼさず見えた2回は `failed`。19:23:33開始は端末57.1秒、対応する公開APIのlive分析はHTTP 200・55.6秒で `live_analysis_failure`。19:26:36開始は端末31秒、対応するlive分析はHTTP 200・29.3秒で同じ失敗ログ。19:25台にも27.9秒の失敗ログが1件ある。19:24:32開始の19.1秒のlive分析には失敗ログがない。成功メタデータは更新されていないため、この1件を成功とも失敗とも記録しない。
- 19:42以降、live失敗は固定段階を残す。公開00018-habのCloud Loggingに `live_analysis_failure stage=engine_http_rejection` を確認。実機オーバーレイの直近8行にも同じ行がある。原文・tokenは出していない。この段階名はAgent Engineのqueryが200以外だったことまでで、拒否理由の本文は未取得。
- 同意撤回、ログアウト、休止提案の接続はまだしていない。`live_last_alignment=unknown` は失敗後も残る前回値なので、running/failedの結果と読まない。

## 到達点

**実機のYouTube利用中に、自動でAgentを起動するところまで接続済み。** Agent Engine上のADK/GeminiがToolを呼び、本人のゴールとの関係を判断する。手動分析ボタンは不要。待機中・判定中の状態オーバーレイも実機で確認した。

ただし、ゴール乖離時の休止提案画面と本人選択による10分休止・解除は未完成。MVP全体の完成ではない。

## 本人が指定した要件

- YouTube利用中にAgentが行動を観測し、ゴールから離れた可能性があればオーバーレイで休止を提案する。
- 利用開始から60秒後に初回、以後60秒周期。クリック2件・新クリックを開始条件にしない。実行中の周期はスキップし、積み残しを連続実行しない。準備遅延と最小間隔により実開始は多少ずれる。
- 有効時のアイコンは「待機中」「判定中」。判定中は実際の通信・分析処理に結び付ける。
- 判定はAgentが必要なToolを呼んで行う。端末の収集機能が観測を記録し、Agentは認可された記録をToolで読み取る。端末や別アプリを自由に探索するAgentではない。
- 独立した自動分析同意を必要とする。既存クラウド同意から自動でONにしない。実機では本人がONにした状態を確認済み。
- 未承認の制限は禁止。本人が選んだ場合だけ10分休止し、即時解除できる仕様。
- エミュレータは今後使用しない。WebデモはMVP対象外。実機インストール・操作デバッグは本チャットで許可済み。

現行正本: [REQUIREMENTS_v0.3.md](../requirements/REQUIREMENTS_v0.3.md)。旧v0.2 DOCXは履歴。

## 実際の処理経路

```text
Accessibility / UsageStats → Roomに実観測を保存
  → YouTube前面・画面ロック解除・権限・本人同意・ログインを確認
  → MinuteAgentScheduleが1分周期を管理
  → LiveAgentRuntimeが直近100イベント以内の不変snapshotを作成
  → Firebase本人認証付きCloud Runへ登録・分析要求
  → Agent EngineがFirebase本人認証を再検証
  → ADK調査AgentがToolで情報を収集
  → 構造化出力Agentがgoal_alignmentと根拠を返す
  → サーバー/端末がID・所有者・根拠・同意・文脈・鮮度を検証
  → 状態アイコンを待機中へ戻し、詳細に前回の概要を表示
```

Agent起動メッセージは調査対象のsession IDを指定する。全イベントをそのまま起動プロンプトへ詰める構成ではない。端末からクラウドへの観測保存は必要で、Toolは保存された当該snapshotだけを読む。

必須Tool: `get_user_goals`、`get_timeline`、`get_click_events`。`get_past_feedback`は必要に応じて呼ぶ。書込Toolはない。過去回答のlive viewは当該sessionに限定される。

モデルは`gemini-2.5-flash`。1分析最大8モデル呼出・12Tool・45秒。Cloud Run待機60秒、Engine transport read55秒、Android分析read65秒。端末の収集IOと通信IOは分離されている。

## 確認できたことと限界

| 項目 | 現状・証拠 |
|---|---|
| 実機自動分析 | 手動ボタンなしでrealデータ1件を分析。必須3Tool、`insufficient_data / unknown`を受信。観測不足を推測で埋めていない |
| 実機状態アイコン | 緑の待機中・紫の判定中を実画面で確認。タップで詳細/設定/OFFが開く |
| 継続実行 | 記録時点で開始7回・終了6回、最新1件はrunning。これは成功7回という意味ではない |
| 古い応答の除外 | 34イベントの分析後に`discarded`を確認。除外理由の詳細は保存していないため、クリック変更等のどの条件だったかは断定しない |
| クラウドlive分析 | 架空sampleでcompleted、必須3Tool・根拠・owner分離・再送・削除世代を検証。一時Auth2件を削除済み |
| 既存手動分析の回帰 | QA00016-poyで18.9秒/completed、認可・訂正・削除・旧世代拒否PASS。一時Auth2件削除済み |
| Firestore | 不変再送・原子的claim・予算・期限・削除をsampleで検証。24h expires_atのTTL3種はACTIVE |
| ローカル検証 | Android36件/失敗0、assembleDebug成功。server61件PASS。docsテスト4件PASS、31チケットvalidate PASS、git diff --check PASS |
| 未確認 | realでのゴール乖離の妥当性、全画面取得、本人訂正の実機保存、撤回/ログアウト/再起動の一連の実機試験、休止提案と休止/解除 |

証拠:

- [android-live-runtime.json](../../artifacts/tests/android-live-runtime.json): 最新実機の安全な状態メタデータとAPK差分。
- [live-api-smoke.json](../../artifacts/cloud/live-api-smoke.json): live APIのsample実クラウド試験。
- [engine-tag-api-smoke.json](../../artifacts/cloud/engine-tag-api-smoke.json): 既存手動経路の回帰。
- [live-firestore-smoke.json](../../artifacts/cloud/live-firestore-smoke.json): Firestore実接続。
- [live-engine-log-audit.json](../../artifacts/cloud/live-engine-log-audit.json): その検証時刻周辺729件でsample原文/JWTパターン未検出。最新の実機自動分析のログ監査を代替しない。
- [android-real-device-debug.json](../../artifacts/tests/android-real-device-debug.json): 先行した実データ手動同期8セッション・分析2件・根拠表示。

実原文・スクリーンショット・認証値は今回の引き継ぎに含めていない。実機状態の`live_last_alignment`/Tool数は直前の成功結果が残る場合がある。runningの結果と読み替えない。

## ソースと実機APKの差分（重要）

分析ログ付きAPKは **2026-10-11 19:39 JST に実機へインストール済み**。1行ラベルとクリックsequence破棄を含む。

ローカルAPK SHA-256:
`25f278cd528ba38f23a8f54afb6b5c3a5a16a121c07d06ccd77e225ab53328fc`

19:23に入れた前版は `ed277c113b6c8b38c6a9d4eff027015f1ea381490b09314e564a0a36313dbc28`。19:15時点の実機は `c8e829125f474553116472f07e2abc73c9432f6203547edb6a42520d74f33714`。

## クラウドと端末

| 項目 | 値 |
|---|---|
| GCP / Firebase | `dopadopa` / project number `463106527119` |
| Cloud Run | `dopaguard-api` / `asia-northeast1` |
| 公開revision | **`dopaguard-api-00018-hab`、100%**。タグ `engine-qa` と `analysis-log` も同じ |
| Androidの公開API | `https://dopaguard-api-463106527119.asia-northeast1.run.app` |
| QAタグ | `https://engine-qa---dopaguard-api-p4kp2phc5q-an.a.run.app`、現在公開と同revision |
| Agent Engine | `projects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656` |
| 最新Engine更新LRO | `4982914266752352256`、done=true/deployed |
| 最新Cloud Build | `41587a3a-4b0e-4a8b-9303-b83c4023d3ba`、SUCCESS。イメージ `sha256:391c88e48e6d9a21b7e55aa14ea085291a1469e9571a417a9e271d4f348b4c3b` |
| Cloud Run SA | `dopaguard-api@dopadopa.iam.gserviceaccount.com` |
| Engine SA | `dopaguard-engine@dopadopa.iam.gserviceaccount.com` |
| Firestore | `(default)` / `asia-northeast1`、snapshots/live_analyses/live_actions TTL ACTIVE |
| 実機 | Pixel 9a / Android 17 / Wi-Fi ADB |
| ADB serial（変わり得る） | `adb-52051JEBF06216-IbqWgB._adb-tls-connect._tcp` |
| package | `com.example.dopadopa` |

Engine/Cloud Build/Gradleの既知の進行中ジョブはない。再開時に状態を照合し、同じEngineを新規createしない。実機の自動分析はONで、YouTubeが前面なら継続する。今回の資料作成でOFFにはしていない。

## 開発チケット

| チケット | 状態 | 残作業 |
|---|---|---|
| DG-300 | done | v0.3要件と追跡表 |
| DG-301 | done | 60秒Policy単体。全体の実機合格ではない |
| DG-302 | in-progress | APIは公開済み。全受入の証拠整理、最新ログ監査、断続的transport失敗の確認 |
| DG-303 | in-progress | 自動実行接続済み。最新APK反映、複数周期/撤回/ログアウト/通信失敗/古い応答の実機検証 |
| DG-304 | in-progress | 状態アイコン接続済み。乖離提案・根拠・本人選択の画面は未完成 |
| DG-305 | backlog | 本人選択による10分休止と即時解除 |
| DG-306 | in-progress | 独立同意/ゴール変更実装済み。権限状態表示と実機変更/撤回検証 |
| DG-307 | backlog | 回答保存と再起動を跨ぐ予算/cooldown/休止復元 |
| DG-308 | backlog | RA-01〜09の実機E2E |

旧DG-001/DG-004のblockedなどは過去の受入状態が残っている。インストール・クリック取得成功だけで旧チェックをまとめてdoneにしない。証拠を個別に照合する。

## 次の担当者がすること

1. 分析ログは実機オーバーレイとCloud Loggingで見える。19:42:21の段階は `engine_http_rejection`。次はこの拒否のHTTP状態を、応答本文を残さずに切分ける。
2. DG-303の受入: 実行中に新クリック/YouTube離脱を行い、古い結果を提案に使わないことを確認。撤回/ログアウト/通信失敗でも収集IOが止まらず、即時無限再送がないことを確認する。
3. DG-304: `finishAndAllowSuggestion(...)`の戻り値が今は未使用。trueのときだけ乖離提案を表示する処理を接続する。表示直前にforeground/入力UI/ロック/同意/文脈/鮮度を再検証。sample/unknown/aligned/失敗では提案しない。
4. DG-305/307: 明示的な本人選択だけで休止・解除・回答保存を行う。端末Policyの予算/cooldownは現状メモリのみ。サーバー予算はFirestoreに永続化済みだが、端末の復元を代替しない。
5. 実機受入と原文を含まないログ監査を揃えてからチケットを閉じる。断続的Engine timeoutは過去にあり、直近成功だけで恒久解消と言わない。

## コードの入口

- `intervention/MinuteAgentSchedule.kt`: 1分周期・実行中スキップ・準備時間差の調整。
- `intervention/LiveAgentRuntime.kt`: 世代取得→Room snapshot→送信/分析→応答検証→表示状態。
- `intervention/LiveAnalysisPolicy.kt`: 60回/時、同時1件、60秒鮮度、根拠、提案10分間隔。
- `intervention/AgentRunState.kt` / `AgentStatusOverlay.kt`: 実requestの状態とAccessibility overlay。
- `collector/GuardAccessibilityService.kt`: 前面/機密UI/ロック/同意の確認、runtime tick、クリックsequence更新。
- `cloud/CloudRepository.kt`: live認可、取消epoch、複数connectionの取消、live APIと65秒待機。
- `data/GuardDatabase.kt` / `TimelineRepository.kt`: 最新100イベント読取、実観測sessionの取得。
- `server/app.py` / `live.py` / `store.py`: 不変snapshot、Tool読み取り範囲、原子的予算/削除/TTL。
- `server/engine_agent.py` / `adk_agent.py` / `remote_engine.py`: Firebase再検証、Tool調査、構造化出力、輸送境界。

Androidソースの基点は`app/app/src/main/java/com/example/dopadopa/`。

## 再開環境と未コミット作業

workspace: `/Users/syl/workspace/dopadopa`、branch: `main`。基点commitは`b60abdbd20fe339eb0ea3be1744907bf140ac2ac`。**今回の開発・引き継ぎ資料は未コミット/未push。Git cloneだけではこの最新状態は取得できない。** 未追跡の新規コード/チケット/証拠も含めてworkspaceを保持する。`.idea/`は既存の未追跡項目なので勝手に削除しない。

Macのローカル確認:

```sh
cd /Users/syl/workspace/dopadopa
.venv/bin/python -m unittest discover -s server/tests -v
python3 -m unittest discover -s docs/scripts -p 'test_*.py'
python3 docs/scripts/tickets.py validate
cd app
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash ./gradlew testDebugUnitTest assembleDebug
```

wrapperは実行bitがないため`bash ./gradlew`を使用。Python環境は3.13、固定依存は`server/requirements.lock.txt`。別端末では実在するJDK/SDKパスに設定し、認証キャッシュ・秘密鍵・`.venv`・端末DBをリポジトリへ含めない。

実機更新（serialはまず`adb devices`で照合）:

```sh
/Users/syl/Library/Android/sdk/platform-tools/adb devices
/Users/syl/Library/Android/sdk/platform-tools/adb -s adb-52051JEBF06216-IbqWgB._adb-tls-connect._tcp install -r app/app/build/outputs/apk/debug/app-debug.apk
```

本人の端末で既存の全instrumentationテストを無差別に実行しない。DB/同意/ログインを変更するテストがある。sampleのクラウド試験は`tools.verify_live_api`、`tools.verify_live_firestore`、`tools.verify_cloud_api --engine-qa`。これらは新規課金可能な実接続試験であり、保存済み証拠の閲覧とは区別する。

19:15の資料作成中は追加の実装・配置・実機インストールをしていない。19:23の再開で、既存のdebug APKを実機へ更新した。クラウドの再配置はしていない。
