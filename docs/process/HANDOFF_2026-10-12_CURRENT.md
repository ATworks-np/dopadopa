# DopaGuard 引き継ぎ — 2026-10-12

この資料を再開時の入口とする。10月12日のユーザー指示、実装、実機更新、検証エラー修正のクラウド反映までを整理した。旧HANDOFFやREADMEの過去検証欄より本資料を優先する。MVP全体の受入は未完了。

## 最初に把握すること

- AndroidのYouTube利用中に、Accessibilityで取得した文字・画面遷移を記録し、1分周期でAIがゴールとの関係を分析する。OCRは使わない。
- 行動理解は単一のADK `LlmAgent`。Tool調査と構造化回答を同じエージェントが担当する。
- ゴールは5項目から複数選択。提案の選択肢は「続ける」「休む」の2つで、「理由を見る」はカード内アコーディオン。
- 設定の「提案通知の間隔」は1〜20分のスライダー、初期10分、自動保存。分析周期1分と、本人が選んだ休止時間10分は別。
- 検証失敗は同じエージェントへエラーと前の回答を返し、最大2回修正する。クラウド反映済み。実Geminiでの修正往復成功は未確認。
- 実機Pixel 9aへ通知間隔設定までのAPKを上書き済み。最後の確認ではAccessibilityがBound、Crashedなし、待機アイコン表示。今回は実機の操作・再インストールをしていない。
- **この利用中の実機でinstrumentationを実行しない。** これまで2回、実行終了後にAccessibilityが非Bound/Crashedとなり、オーバーレイが消えた。エミュレータも使わない。
- `main`のHEADは`f233a16`。今回のソース・証拠・資料には未コミット/未追跡の変更が多数ある。別端末にはGitのHEADだけでは移せない。`.idea/`は既存の未追跡ファイルなので保持する。

## ユーザーが決めた仕様

| 項目 | 現行仕様 |
|---|---|
| 対象 | YouTubeのみ。内部のapp_id/package_nameとAdapterの境界は維持 |
| 取得 | ユーザー補助で公開コンテンツの関連文字を読む。入力欄・認証・DM等は除外。取得できない文字を推測しない |
| CLICK | 廃止していない。クリック通知が来た場合は記録する |
| 画面遷移 | クリック通知がなくても、表示タイトルの変化をWINDOW_CHANGEとして記録する |
| 分析開始 | 利用開始60秒後、以後60秒周期。新クリックやクリック2件を条件にしない。実行中の周期はスキップ |
| 結果受入 | 新クリックや60秒経過だけで結果を破棄しない。同意撤回・owner/ゴール変更・離脱・ロック等の取消は維持 |
| 目標設定 | 学習・スキルアップ / 情報収集 / 趣味・娯楽 / 休憩・気分転換 / 長時間利用を減らす。1項目以上で明示保存 |
| アプリ画面 | ホーム / 操作履歴 / 設定。振り返り・分析タブ・手動同期導線は削除 |
| エージェント | 行動理解は1つ。目標設定のAI対話は使用しない。旧相談APIは残るがAndroidの呼出導線なし |
| 提案UI | 明るい角丸の下部カード、短い提案、理由アコーディオン、固定の2ボタン「続ける」「休む」 |
| 休止 | 本人が「休む」を選んだ場合だけHOMEへ移動し10分休止。即時解除可能。未承認の強制ブロック禁止 |
| 配布 | 以前の指示で直ダウンロードリンクは禁止、招待制。既存App Distribution releaseは最新APKとは限らない |

現行要件は[REQUIREMENTS_v0.3.md](../requirements/REQUIREMENTS_v0.3.md)。イベント契約は[EVENT_CONTRACT_2026-10-12.md](EVENT_CONTRACT_2026-10-12.md)。変更理由は[RISKS_AND_DECISIONS.md](RISKS_AND_DECISIONS.md)。

## 現在の処理経路

```text
Accessibility / UsageStats → Roomに観測を保存
  → YouTube前面・ロック・機密UI・個別同意・ログインを確認
  → MinuteAgentSchedule（1分周期、同時1件）
  → LiveAgentRuntime（同意以後の直近100イベント以内のsnapshot）
  → Firebase本人認証付きCloud Run（登録・分析要求）
  → Agent Engine（本人認証と削除世代を再確認）
  → 単一LlmAgent dopaguard_behavior（Tool調査・構造化回答）
  → 回答形式・ID・必須Tool・観測根拠を検証
     ├─ 不正 → 同じ一時ADKセッションへ検証エラーと前の回答 → 最大2回修正
     └─ 正常 → サーバー/Androidの検証 → 提案条件・通知間隔の判定 → オーバーレイ
```

必須Toolは`get_user_goals`、`get_timeline`、`get_content_events`。任意で`get_past_feedback`。書込Toolなし。旧`get_click_events`は過去trace読取の互換性のみで、新Agentには公開しない。

`get_timeline`は文字を除いた時系列情報、`get_content_events`はCLICK/WINDOW_CHANGEの文字・取得状態・source/observabilityを返す。文字ありWINDOW_CHANGEも分析/提案の根拠にできるが、「画面にタイトルが表示された」という観測であり、クリック・視聴・注視の証拠にはしない。missing/redactedを補完しない。非内容イベントや時間差だけでは内容を断定しない。

## 今回最後の変更: 検証エラーの修正

`server/adk_agent.py`で、回答形式・ID・根拠・必須Toolの不足を同じエージェントへ返す。初回回答に加えて最大2回修正。修正を含めて45秒・8モデル呼出・12Toolの上限を共有し、通過した結果だけ採用する。

修正用メッセージに検証の不正項目と前の回答（最大20000文字）を含める。回答は信頼できないデータとして扱い、その中の命令に従わないよう指示する。メッセージと一時セッションはメモリ内のみ。認可・データ削除・Tool実行・通信の失敗を、この回答修正で再試行しない。

ADKの`output_key`自動検証は不正入力を含む例外がログへ出るため使わず、response schemaを維持してアプリ側で検証する。Cloud RunとAndroidの追加検証は維持。

- 模擬providerで、根拠捏造・confirmation false・schema・ID・Tool不足からの修正成功を確認。
- 修正上限、共有モデル呼出上限、修正中deadline、削除取消、一時セッション削除を確認。
- 実Geminiを使った「初回検証だけ意図的に失敗させる」ローカルADK試験は45秒deadlineで失敗。修正往復の成功証拠にはしない。[engine-repair-smoke.json](../../artifacts/cloud/engine-repair-smoke.json)。架空データのみ、MemoryStore使用。
- 更新後の実Gemini/Engine/公開APIの通常分析はsampleで成功。修正を含む実利用の安定性は次の確認事項。

## 実機取得とUIの検証状況

Accessibility実機デバッグでは、公開コンテンツを実際にタップし、表示後のタイトル一致を確認した。クリック通知が0でも画面内容変更通知が届く場合があり、表示直後のTextViewから全幅の仮想Buttonへ変わる構造にも対応した。プレーヤー直下へ限定し、おすすめ・評価数・ミニプレーヤー等を除外する。同名タイトル間の移動は区別できない。

全画面・全コンテンツで取得できると主張しない。古い誤取得履歴を現在の正しいタイトルとみなさない。実原文・端末DB・認証値をGitに保存しない。

提案UIはサンプルのnativeカード試験で理由展開・2ボタンのコールバックを確認。実AI結果による提案・休止・解除の一連の受入とは区別する。ユーザーは改善後の動作を確認しているが、RA-01〜09全体の受入証拠は揃っていない。スライダーはビルド・単体・APK反映済みで、実画面の配置確認は未達。

## オーバーレイが出ない場合

1. **AccessibilityがBoundか確認する。** OS上でEnabledでも、非Bound/Crashedなら待機アイコンも提案も出ない。instrumentationとAPK更新後に実際に発生した。
2. `possibly_diverged`だけで提案表示は保証されない。コメント・理由・観測根拠・前面/ロック/同意などに加え、設定した通知間隔の条件がある。
3. `suggestion_last_wall`は提案作成時点、Policyのcooldownも許可時点で更新される。実際のカード表示成功を証明する値ではない。表示失敗でも通知間隔を消費する可能性がある。この仕様は未修正。
4. `live_last_alignment`やTool数は前回成功の値が残ることがある。現在のrunning/failedの判定と混同しない。

既存ONのGuardだけ非Boundになっている場合は、他のサービス・同意を保ったまま再接続する。`enabled_accessibility_services`の元値をメモリで保持し、Guard項目だけ一時的に除いてから元値へ戻し、Bound/Crashedを再確認する。OFFだったサービスを勝手にONにしない。**最後のインストール後**に確認する。

## failedの既知の原因と残課題

10月12日の実利用診断では、11:53/11:54/11:59に`engine_transport`、11:54に`engine_result_validation`、12:00に`engine_http_rejection`を確認した。11:55にはcompletedへ復帰した。

- `engine_transport`: Cloud Run → Agent Engineの通信段階。端末Wi-Fiの失敗と決めつけない。timeout/接続等の詳細は現ログだけでは未特定。
- `engine_result_validation`: Engine内で回答の検証が失敗。今回の修正往復で対処するが、実Geminiでの回復確認は残る。
- `engine_http_rejection`: Engine queryがHTTP200以外。状態コードの安全な切分けが必要。
- `model_deadline`: Agent全体45秒上限。修正にも同じ上限を適用。時間を伸ばせば解決するとは判断しない。

ログには固定stage/status/alignment/Tool名だけを残す。Cloud Loggingのprovider本文、イベント原文、トークン、IDを出力しない。cold start・モデル混雑等は根拠なしに原因と断定しない。

## 配置済みクラウド

| 項目 | 現在の値 |
|---|---|
| GCP / Firebase | dopadopa / 463106527119 |
| Cloud Run | dopaguard-api / asia-northeast1 |
| 公開API | https://dopaguard-api-463106527119.asia-northeast1.run.app |
| 公開traffic | **dopaguard-api-content-single-1035へ100%**。本引き継ぎ作成時にread-onlyで再確認 |
| Image digest | sha256:6e8cb7c368d79bf32463b7c0c64e56f853217ae3be199f1cb54c3e69d140dde5 |
| Cloud Build | 9b98e141-c931-4e76-bb5c-d014bb8f442f SUCCESS |
| Agent Engine | projects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656 |
| 最後のEngine更新LRO | 5163668480800587776、更新完了、通常推論確認済み |
| Model | gemini-2.5-flash / global |
| Cloud Run SA | dopaguard-api@dopadopa.iam.gserviceaccount.com |
| Engine SA | dopaguard-engine@dopadopa.iam.gserviceaccount.com |

同じEngineを新規createしない。既存resourceへ`tools.deploy_engine --update`する。古い`engine-qa`/`analysis-log`タグは00018-habを指すため、最新API試験に使わない。既知の進行中deploy/build/Gradleジョブはない。

待機上限はモデル全体45秒、Engine worker50秒、Cloud Run → Engine read55秒、Cloud Run request60秒、Android分析read65秒/connect10秒。今回ここは変更していない。

Firestore本人データは`dopaguard_users/{sha256(firebase_uid)}`以下。実ユーザーで削除試験をしない。公開経路の検証はdisposableなFirebaseアカウントと架空sampleで実施する。

## 検証結果と証拠

| 検証 | 最後の結果 | 範囲・限界 |
|---|---|---|
| server全体 | 84件PASS | ADK修正16件を含む。修正試験は模擬provider |
| Android単体 | 49件、失敗0 | 通知間隔変更まで。最後の変更はserverのみ |
| Android assembleDebug | PASS、Pixel 9aへ上書き済み | 通知間隔設定まで。今回再インストールなし |
| 実Gemini/Engine/公開API | sample aligned / API completed | クリック0・WINDOW_CHANGEタイトル根拠。実利用の妥当性を保証しない |
| 認可境界 | PASS | 無効token、別owner、削除世代を拒否。一時2アカウント/架空データ清掃済み |
| 更新後Engineログ監査 | PASS、27件 | sample文字列/JWT検出なし、telemetry無効 |
| 実Geminiの意図的な検証失敗修正 | deadlineで失敗 | 修正往復成功は未確認 |
| docs | 4件PASS、38チケットvalidate PASS | 本書更新後に再確認済み。リンク先存在/git diff --checkもPASS |

証拠: [engine-smoke.json](../../artifacts/cloud/engine-smoke.json)、[engine-deployment.json](../../artifacts/cloud/engine-deployment.json)、[engine-operation-status.json](../../artifacts/cloud/engine-operation-status.json)、[engine-log-audit-current.json](../../artifacts/cloud/engine-log-audit-current.json)。`engine-package.json`のprepared/deployedフラグはパッケージ準備時の値なので、配置の成否はdeployment/operation/smokeを優先する。

## 主要コードと未コミット変更

Androidの基点は`app/app/src/main/java/com/example/dopadopa/`。

| 入口 | 担当 |
|---|---|
| collector/YouTubeAdapter.kt、TextObservation.kt、ScreenTextCache.kt | Accessibility文字取得と画面タイトル識別 |
| collector/GuardAccessibilityService.kt | 前面/権限/同意、収集、オーバーレイ接続、通知間隔の永続判定 |
| intervention/LiveAgentRuntime.kt、MinuteAgentSchedule.kt | snapshot、1分周期、通信、結果検証 |
| intervention/LiveAnalysisPolicy.kt、SuggestionInterval.kt | 同時1件・提案条件・1〜20分通知間隔 |
| intervention/SuggestionCard.kt、InterventionOverlay.kt | 提案カード・理由アコーディオン・本人承認休止 |
| intervention/AnalysisDiagnostics.kt、AnalysisExplanation.kt | 固定診断と任意表示の説明 |
| MainActivity.kt、ui/GoalSelection.kt | ホーム/履歴/設定、5項目複数選択、通知間隔設定 |
| server/adk_agent.py、engine_agent.py | 単一Agent、修正往復、認可/削除世代、一時セッション |
| server/agent.py、models.py | プロンプト、Tool、観測根拠の検証 |
| server/app.py、remote_engine.py | APIとEngine呼出・安全な失敗段階 |
| tools/verify_engine.py | 実GeminiとAPIのsample/認可境界試験 |

`UsageConversation.kt`は削除。新規のScreenTextCache/SuggestionCard/SuggestionInterval/GoalSelectionやテスト、イベント契約、engine-repair-smoke.jsonは未追跡を含む。変更ファイルの完全な一覧は`git status --short`で確認する。`.idea/`、.venv、認証ファイル、端末DB、実画面、/tmpの診断ファイルを移送用Gitへ混ぜない。

## チケットと次の作業

- DG-300/301: done。個別の要件/Policy完了であり、MVP全体合格ではない。
- DG-302/303: in-progress。通信失敗の細分化、実Geminiでの検証修正往復、撤回/ログアウト/取消の実機受入。
- DG-304/305/307: in-progress。提案/休止/解除の実機E2E、表示成功前のcooldown消費、再起動を跨ぐ状態・予算。休止は再接続/プロセス再起動で解除し、残時間復元ACは未達。
- DG-006/306/314: in-progress。Accessibilityの取得範囲、同意/権限/目標保存の受入。
- DG-308: backlog。RA-01〜09の全体受入証拠を揃える。

次の優先順:

1. 修正往復でどこまで進んだかを原文なしの固定メタデータで切り分け、実Geminiの回復を確認する。通信・モデルdeadlineを回答検証失敗と分ける。
2. `engine_transport`/HTTP拒否の状態・時間を安全に取得する。原因を推測で埋めない。
3. 通知間隔内の抑止と、表示失敗によるcooldown消費を分ける。必要なら「表示成功時に更新」へ修正する。
4. 実機ではinstrumentationを使わず、本人の操作に合わせて提案→休む→解除、設定1分/20分、同意変更を検証する。本人設定を勝手に変更しない。
5. 残ACを確認し、証拠が揃ってからチケットを閉じる。

## 再開コマンド

```sh
cd /Users/syl/workspace/dopadopa
git status --short
.venv/bin/python -m unittest discover -s server/tests -q
python3 -m unittest discover -s docs/scripts -p 'test_*.py' -q
python3 docs/scripts/tickets.py validate
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash ./app/gradlew -p app testDebugUnitTest assembleDebug
/Users/syl/Library/Android/sdk/platform-tools/adb devices
```

wrapperは`bash`で呼ぶ。Python3.13、依存は`server/requirements.lock.txt`。別端末では実在するJDK/SDKパスを使い、Google Cloud認証を自分の環境で設定する。gcloudはこの端末では`/Users/syl/Library/google-cloud-sdk/bin/gcloud`。

実機のwireless serialは変わり得る。`adb devices`で対象を確認してから操作する。APKは`app/app/build/outputs/apk/debug/app-debug.apk`。再インストール後は必ずAccessibilityのBound/Crashedを確認する。**今回の引き継ぎ作成で新しいデプロイ・推論・APK更新・実機操作は行っていない。**

## 2026-10-12 追記: 粒子オーバーレイの更新（DG-304）
本人指定でOverlaySignalを16個の点から、144粒子の立体球・96粒子の3本の流れ・36個の外周浮遊へ変更。シアン/紫/白、奥行き、明滅、短い残光、待機→分析の滑らかな加速。既存96dpのタップ/ドラッグ領域は維持。57単体/ビルドPASS、Pixel9aへ上書き成功、Guard Bound/Crashedなし、新しい球の実表示を確認。判定中の遷移/性能実測は未確認。利用中の実機でinstrumentationは行わない。資料本体の旧検証数49はこの追記の57へ更新する。

## 2026-10-12 ボックス・名称と分析中の発光
- 本人指示でAgentStatusOverlayに角丸の濃紺ボックス/細い枠/DopaGuard名称を追加。幅112dp固定で詳細展開時もボックスが引き伸ばされない。タップ詳細/長押し移動はボックス全体で操作、読み上げは名称と待機/分析状態を統合。
- OverlaySignalの分析中はエネルギー値で紫からシアン/ピンク/白へ滑らかに変化。粒子の輝度/光輪/中心の光を増強。新しいgradientを再利用し毎フレーム生成なし。
- Android単体57件/失敗0、assembleDebug PASS。Pixel9a上書き成功、Guard Bound/Crashedなし。ボックスと名称の実表示を確認。実分析中の色遷移はまだ未確認。instrumentation/エミュレータなし、個別同意/間隔を変更せず。画像は/tmpのみ、Git原文保存なし。

## 2026-10-12 詳細展開の横幅と文字
- 本人指示でAgentStatusOverlayを折畳み112dp/展開時最大360dp（画面幅-24dp）に変更。展開時ヘッダーは粒子64dpとDopaGuard18spを横並び。詳細は15sp/行間4dp/余白18dp、画面内へ位置補正し、閉じると元の位置に戻す。長押し移動時は折り畳む。
- testDebugUnitTest/assembleDebug PASS、実機上書き成功。既存ON GuardのBound/Crashed確認。今回instrumentationなし。展開実画面の確認は未実施。既存AC未完のためDG-304 in-progress。

## 2026-10-12 今すぐ分析の修正と実機確認
- ForegroundWindowで自分のAccessibility overlayを前面判定から除き、背後の最上位アプリがYouTubeの場合のみ取得/分析継続。他アプリ/入力UI/不明な前面窓は拒否。新規5単体で境界確認。
- 実クラウドHTTP集計でanalyze 429が6件/200が4件。manualだけクライアントの最小間隔を無視していたためサーバー60秒上限と整合し、残り秒数を表示。HTTP429は通信失敗でなくskipped/rate_limitedに分類。
- 62単体/ビルドPASS、実機更新とGuard Bound/Crashedなし。実際にoverlayを開き今すぐ分析を押下、15:33:40にinsufficient_data/unknown/必須3Toolで正常終了を確認（観測不足でありAI故障/取消ではない）。表示/原文は/tmpのみ。docs4件/38チケットvalidate/diff-check PASS。in-progress維持。

## 2026-10-12 無秩序の粒子と浮遊する盾
- 本人指示でOverlaySignalの球/軌道/残光を廃止。276粒子を個別の位相・速度で漂わせ、分析中だけ同じ粒子が盾の外周/内周/内部へ滑らかに集合。シアン/ピンクへ発光色変化。盾の状態でも独立漂いと輪郭の波/左右上下の浮遊/傾き/呼吸を継続し、追加指示で揺れを増強。
- 62単体/ビルドPASS、実機上書き成功。最初の盾版で実分析中の盾シルエットを実画面で確認。追加の揺れ増強もビルド/実機反映済み、動きの最終受入は本人確認待ち。更新前に分析が終了済みであることを確認してからinstall。Guard Bound/Crashedなし、instrumentation/エミュレータなし、画像は/tmpのみ。DG-304 in-progress継続。

## 2026-10-12 Engine HTTP拒否への対処（DG-303）
- 実機16:04:09の固定診断はfailed/engine_http_rejection/Toolなし。対応時間のクラウドログに詳細HTTP番号は残っておらず、過去失敗の根本原因は未確定。原文ログは/tmpのみ、Gitへ保存しない。
- RemoteEngineRunnerは429/502/503/504のみ1秒後に1回再試行。認可/引数エラーや500、通信例外、モデル/検証失敗を無条件に再実行しない。再試行前に削除世代とsnapshotの同意/有効性を確認、同一analysis/snapshotを使用。HTTP番号・試行番号だけをログ、最終失敗のengine_http_statusを保存/返却。provider本文/token/観測原文を記録しない。
- server89件PASS（503から200の復帰、再試行上限、403/400/500を再試行しない、診断の秘密除外）。APIクラウド反映と実Gemini sample検証は続行中。DG-303 in-progressを維持。
- 先行する連鎖検知の実Gemini sample検証: related-chainはaligned/possible_chaining/8根拠、learning-onlyはaligned/no_clear_chaining、公開APIもcompleted。一時アカウントと架空データ清掃済み。実利用の判定成功の証拠にはしない。

- Cloud Build 93eb95f5-ba46-424b-8bd7-443a666e254d SUCCESS。API digest7a67ba01718bc3231815c2c5eedad11f2bc99e8e1e9c9858dceb04e7084d4717、revision dopaguard-api-http-recovery-1620へ公開traffic100%反映。Engine/Androidの再配置なし。
- 最終server90件PASS（再試行待機中の削除で再送しない試験を追加）、docs4件PASS、38チケットvalidate/diff-check PASS。
- 実Gemini sampleと新公開APIの分析はaligned/no_clear_chaining/completed、取得タイトル根拠検証PASS。ただしその後の認可境界チェックのEngine queryでReadTimeoutとなり全体status=failed。今回の境界チェック全体PASSとは主張しない。一時アカウント2件/架空データ清掃済み。証拠artifacts/cloud/engine-smoke-http-recovery.json。実機の断続的失敗の再発有無は継続確認が必要。過去のHTTP拒否の正確な番号は復元不可。
