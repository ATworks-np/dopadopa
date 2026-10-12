# 現在の状態からの引き継ぎ

## 最新追記: 2026-10-12 休止提案・本人承認休止のコード実装

以下の旧到達点の「休止未実装」は、この追記で更新する。DG-304/305/307をコード・ビルド範囲で実装。検証済みpossibly_divergedと実クリック根拠がある新鮮な結果だけをInterventionOverlayへ接続。表示/ボタン時に前面・ロック・機密UI・同意・owner・クリック文脈・鮮度を再確認。根拠を見る/10分休む/続行/目的に沿っている/判断できない/停止/閉じるを提供。

本人が休止を選んだ場合のみHOMEを実行。elapsedRealtimeで10分管理し、YouTube再表示で残時間・今すぐ解除・HOMEを表示。期限・撤回・owner/ゴール変更・HOME失敗・サービス停止/再接続で解除する。プロセス/サービス再起動は旧休止の復元ではなく安全に解除するため、DG-305の残時間復元ACは未達。

本人選択と休止終了を端末内固定語履歴50件に保存しAgent詳細に表示。原文/仮説は永続化せず観測も変更しない。owner変更で履歴を消す。壁時計による10分cooldownは永続化、時計逆行は新しい10分抑止に置換。推論予算の永続復元・クラウド訂正連携は残作業。

Android単体44件/失敗0、assembleDebug成功、docs4件PASS、33チケットvalidate PASS。証拠はartifacts/tests/android-voluntary-pause-build.json。実機・エミュレータの操作は行っていない。DG-304/305/307は実機受入が残るためin-progress。以前のApp Distribution招待APKに本変更は含まれない。engine_http_rejectionの調査も別途残る。

記録時点: **2026-10-12**。この資料を再開時の入口とする。10-11の引き継ぎと旧HANDOFFの「実機保留」「自動実行未接続」「未コミット」は履歴であり、本資料より優先しない。

このcommitに、v0.3の自動分析・状態アイコン・分析ログと、その証拠・チケットを含む。`.idea/`は含めていない。

## 到達点

**実機のYouTube利用中に、自動でAgentを起動するところまで接続済み。** Agent Engine上のADK/GeminiがToolを呼び、本人のゴールとの関係を判断する。手動分析ボタンは不要。待機中・判定中の状態オーバーレイも実機で確認した。失敗時の固定段階は、実機オーバーレイの直近8行とCloud Loggingの両方に残る。

ゴール乖離時の休止提案画面と、本人選択による10分休止・解除は未完成。MVP全体の完成ではない。

## 本人が指定した要件

- YouTube利用中にAgentが行動を観測し、ゴールから離れた可能性があればオーバーレイで休止を提案する。
- 利用開始から60秒後に初回、以後60秒周期。クリック2件・新クリックを開始条件にしない。実行中の周期はスキップし、積み残しを連続実行しない。
- 有効時のアイコンは「待機中」「判定中」。判定中は実際の通信・分析処理に結び付ける。
- 判定はAgentが必要なToolを呼んで行う。端末が観測を記録し、Agentは認可された記録をToolで読む。端末や別アプリを自由に探索するAgentではない。
- 独立した自動分析同意が必要。既存クラウド同意から自動でONにしない。実機では本人がONにした状態を確認済み。
- 未承認の制限は禁止。本人が選んだ場合だけ10分休止し、即時解除できる仕様。
- エミュレータは使用しない。WebデモはMVP対象外。実機インストールと操作デバッグは許可済み。

現行正本: [REQUIREMENTS_v0.3.md](../requirements/REQUIREMENTS_v0.3.md)。

## 処理経路

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
  → 状態アイコンを待機中へ戻し、詳細に前回の概要と分析ログを表示
```

必須Toolは `get_user_goals`、`get_timeline`、`get_click_events`。`get_past_feedback`は必要に応じて呼ぶ。書込Toolはない。モデルは `gemini-2.5-flash`。1分析は最大8モデル呼出・12Tool・45秒。Cloud Run待機60秒、Engine transport read 55秒、Android分析read 65秒。

分析ログは固定語だけを残す。形式は `HH:mm:ss status=... stage=... alignment=... tools=...`。観測原文、トークン、IDは出さない。失敗時の応答には `diagnostic_stage` を付ける。Cloud Loggingは `live_analysis_failure stage=...` または `live_analysis status=... alignment=... tools=...`。

## 確認できたことと限界

| 項目 | 現状 |
|---|---|
| 実機自動分析 | 手動ボタンなしでrealデータを分析。必須3Toolと `insufficient_data / unknown` を確認 |
| 実機状態アイコン | 待機中・判定中を実画面で確認。タップで詳細/設定/OFFが開く。ラベルは1行 |
| 分析ログ | 2026-10-11 19:42:21 JST に `stage=engine_http_rejection` を実機と公開Cloud Loggingで確認。Agent Engineのqueryが200以外だったことまでで、拒否本文は未取得 |
| 周期 | 失敗後も1分周期を継続し、失敗直後の連続再送にはなっていない |
| クラウドlive | 架空sampleでcompleted、必須3Tool、owner分離、再送、削除世代。一時Authは削除済み |
| ローカル検証 | server 63件PASS。Android単体38件/失敗0、assembleDebug成功。docsテスト4件PASS、31チケットvalidate PASS |
| 未確認 | realでのゴール乖離の妥当性、休止提案と休止/解除、撤回/ログアウト/再起動の一連の試験、`engine_http_rejection` のHTTP状態 |

`live_last_alignment` とTool数は前回成功の値が残ることがある。runningやfailedの結果と読まない。

証拠: [android-live-runtime.json](../../artifacts/tests/android-live-runtime.json)、[live-api-smoke.json](../../artifacts/cloud/live-api-smoke.json)、[live-firestore-smoke.json](../../artifacts/cloud/live-firestore-smoke.json)、[live-engine-log-audit.json](../../artifacts/cloud/live-engine-log-audit.json)、[android-real-device-debug.json](../../artifacts/tests/android-real-device-debug.json)。実原文と認証値は含めていない。

## 実機APK

2026-10-11 19:39 JST にインストールしたdebug APKのSHA-256:

`25f278cd528ba38f23a8f54afb6b5c3a5a16a121c07d06ccd77e225ab53328fc`

このcommitのソースから再ビルドした場合、hashは変わる。再開時は `adb shell pm path` で実機のAPKを取り、hashを照合してから入れ直す。

## クラウドと端末

| 項目 | 値 |
|---|---|
| GCP / Firebase | `dopadopa` / `463106527119` |
| Cloud Run | `dopaguard-api` / `asia-northeast1` |
| 公開revision | **`dopaguard-api-00018-hab`、100%**。2026-10-12に再確認。タグ `engine-qa` と `analysis-log` も同じ |
| 公開API | `https://dopaguard-api-463106527119.asia-northeast1.run.app` |
| QAタグ | `https://engine-qa---dopaguard-api-p4kp2phc5q-an.a.run.app` |
| Agent Engine | `projects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656` |
| イメージ | `sha256:391c88e48e6d9a21b7e55aa14ea085291a1469e9571a417a9e271d4f348b4c3b` |
| Cloud Build | `41587a3a-4b0e-4a8b-9303-b83c4023d3ba` SUCCESS |
| Cloud Run SA | `dopaguard-api@dopadopa.iam.gserviceaccount.com` |
| Engine SA | `dopaguard-engine@dopadopa.iam.gserviceaccount.com` |
| Firestore | `(default)` / `asia-northeast1`。snapshots / live_analyses / live_actions のTTLはACTIVE |
| 実機 | Pixel 9a / Android 17 / Wi-Fi ADB。serialは変わり得る |
| package | `com.example.dopadopa` |

同じAgent Engineを新規createしない。進行中のEngine更新、Cloud Build、Gradleはない。実機の自動分析はONのままにしている。

## チケット

| チケット | 状態 | 残作業 |
|---|---|---|
| DG-300 | done | v0.3要件と追跡表 |
| DG-301 | done | 60秒Policy単体。全体の実機合格ではない |
| DG-302 | in-progress | 公開API済み。`engine_http_rejection` のHTTP状態を本文なしで切分ける |
| DG-303 | in-progress | 自動実行接続済み。撤回/ログアウト/通信失敗/古い応答の実機検証 |
| DG-304 | in-progress | 状態アイコンと分析ログは接続済み。乖離提案は未接続 |
| DG-305 | backlog | 本人選択による10分休止と即時解除 |
| DG-306 | in-progress | 独立同意は実装済み。権限表示と実機の変更/撤回 |
| DG-307 | backlog | 再起動を跨ぐ予算/cooldown/休止復元 |
| DG-308 | backlog | RA-01〜09の実機E2E |

## 次にすること

1. `engine_http_rejection` のHTTP状態だけを、応答本文を保存せずに切分ける。
2. DG-303: 実行中の新クリックとYouTube離脱で古い結果を提案に使わないこと、撤回/ログアウト/通信失敗でも収集が止まらず即時無限再送しないことを確認する。
3. DG-304: `finishAndAllowSuggestion(...)` がtrueのときだけ乖離提案を出す。表示直前に前面/入力UI/ロック/同意/文脈/鮮度を再検証する。sample、unknown、aligned、失敗では提案しない。
4. DG-305/307: 明示的な本人選択だけで休止・解除・回答保存を行う。端末の予算とcooldownはメモリ上だけで、再起動では戻らない。
5. 実機受入と原文を含まないログ監査が揃ってからチケットを閉じる。直近の失敗段階だけでtimeoutの解消とは扱わない。

## コードの入口

- `intervention/MinuteAgentSchedule.kt`: 1分周期、実行中スキップ。
- `intervention/LiveAgentRuntime.kt`: snapshot、分析、検証、分析ログ。
- `intervention/AnalysisDiagnostics.kt`: 固定語だけのログ行。
- `intervention/LiveAnalysisPolicy.kt`: 回数、同時1件、鮮度、提案間隔。
- `intervention/AgentRunState.kt` / `AgentStatusOverlay.kt`: 判定中の状態とoverlay。
- `collector/GuardAccessibilityService.kt`: 前面、機密UI、ロック、同意、tick。
- `cloud/CloudRepository.kt`: live APIと65秒待機。
- `server/app.py` / `live.py` / `store.py`: 不変snapshot、予算、診断段階。
- `server/engine_agent.py` / `adk_agent.py` / `remote_engine.py`: 本人再検証、Tool、輸送境界。

Androidの基点は `app/app/src/main/java/com/example/dopadopa/`。

## 再開コマンド

```sh
cd /Users/syl/workspace/dopadopa
.venv/bin/python -m unittest discover -s server/tests -q
python3 -m unittest discover -s docs/scripts -p 'test_*.py'
python3 docs/scripts/tickets.py validate
cd app
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash ./gradlew testDebugUnitTest assembleDebug
```

wrapperに実行bitがないため `bash ./gradlew` を使う。Pythonは3.13、依存は `server/requirements.lock.txt`。別端末では実在するJDKとSDKのパスを使う。認証、秘密鍵、`.venv`、端末DBはリポジトリに含めない。

実機へ入れ直す前に `adb devices` でserialを照合する。既存のinstrumentationテストをまとめて実行しない。DB、同意、ログインを変える試験がある。`tools.verify_live_api`、`tools.verify_live_firestore`、`tools.verify_cloud_api --engine-qa` は課金される実接続試験であり、保存済み証拠の閲覧とは分ける。

## 2026-10-12 招待制配布の追記

本人の指定により直ダウンロードリンクは禁止。旧release 5lr8509kenbe8を削除しGET 404を確認。新release 4t4qnpquf0m08にDG-309 APKをアップロードし、atomic1017@gmail.comだけへの招待API成功。メール到着・招待承認・インストールは未確認。tools/distribute_android.pyは直リンクを出力せず、新uploadに--testerを必須とする。詳細はANDROID_DISTRIBUTION.mdとartifacts/cloud/app-distribution.json。実機・エミュレータ操作は行っていない。
