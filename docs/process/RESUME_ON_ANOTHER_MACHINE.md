# 再開時の入口（2026-10-12）

最初に [現在の引き継ぎ資料](HANDOFF_2026-10-12_CURRENT.md) を読む。実機の1分周期の自動Agent分析、待機中/判定中アイコン、固定段階の分析ログは接続済み。休止提案/休止/解除は未完成。

- 公開Cloud Runは00018-hab。同じAgent Engineを新規createしない。
- ソースはmainにpush済み。実機に入っているAPKは2026-10-11 19:39のdebugビルドなので、再開時にhashを照合する。
- 実機の自動分析は本人がONにしている。YouTube前面時は引き続き動く。エミュレータ不使用/Web対象外。

以下は旧端末時点のセットアップ参考・履歴。状態、保留、ジョブID、次の着手順は現行引き継ぎ資料を優先する。

---

# 過去の別端末向け再開資料（履歴）

## 新端末での最新更新（旧引継ぎ以後）

本チャットで実機インストール/デバッグ再開済み。Pixel 9aの実データ手動同期/分析/根拠表示に成功。続いてユーザー指示で要件をv0.3へ再設計した。現行正本はrequirements/REQUIREMENTS_v0.3.md、開発はDG-300〜308。通知P1/実機保留の旧記述よりこの更新を優先する。

自動起動/提案Policyはローカル実装・単体31件/APKビルド成功。snapshot API、自動同意UI、collector自動実行、overlay、本人承認休止、永続状態、実機E2Eは未完成。エミュレータ不使用/Web対象外は継続。

最初にこの資料を読み、次に [受入状況](MVP_ACCEPTANCE_STATUS.md)、[最新の対応履歴](HANDOFF.md) の末尾、[DG-017](../tickets/in-progress/DG-017.md) を確認する。過去履歴の「進行中」より後の終了記録を優先する。MVP全体は未完成。

## 引き継ぎ確定時の最優先事項

ユーザーの最終指示は「引き継ぎがゴール」。旧端末での追加開発・新配置・新テストは終了する。次端末ではユーザーの作業再開指示に従う。

すでに開始した以下のクラウド処理は最終確認時点で進行中。新端末へ移っても重複投入せず、同じリソース/ジョブの状態を確認する。

- Engine更新LRO: projects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656/operations/1534845802046816256。最新check_engineはdone=false/updating。旧端末session48512も生存確認済み。新端末でこのローカルsession IDは使わず、python -m tools.check_engineでLROを照合する。
- Cloud Build: ffcaa333-732b-46b5-a5d9-e6dfc8e35abb、最終確認WORKING。gcloud builds describeで同じIDを確認する。
- 通常公開Cloud Runは00013-hexのまま。新しいAPIイメージはビルド中で未配置。Engineは共有リソースの更新中なので、完了後は新コードになる。新推論/ログ監査は未実施。
- 下記の「生存handleなし」「クラウド未反映」はこの更新開始より前の状態。今回の処理状態は本節とHANDOFF末尾を優先する。

## ユーザーの最新指示

- WebデモはMVP対象外。既存Webコードは参考として残す。
- 実機テストはユーザーの再開指示まで保留。
- 今後エミュレータを使用しない。接続テスト・インストールを自動再開しない。
- Firebase/GCPはdopadopa、管理アカウントはatomic1017@gmail.com。
- GitHubはATworks-np/dopadopa、mainへプッシュする。

## ソース取得

```powershell
git clone git@github.com:ATworks-np/dopadopa.git
cd dopadopa
git status -sb
git log -5 --oneline
```

GitHub SSH認証は新端末で設定する。旧端末の秘密鍵やGoogle認証キャッシュをリポジトリ経由で移さない。開発仕様はdocsに展開済みで、添付ZIPの再取得は不要。

## 端末を使わないローカル検証

Python 3.13、JDK 21、Android SDK platform 36とAndroid Studioを用意する。Gradle wrapperはコミット済み。

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r server/requirements.lock.txt
.\.venv\Scripts\python.exe -m unittest discover -s server/tests -v
.\.venv\Scripts\python.exe -m unittest discover -s docs/scripts -p 'test_*.py'
.\.venv\Scripts\python.exe docs/scripts/tickets.py validate
```

Android Studioでappを開きSDKを設定する。app/local.propertiesは端末固有のSDKパスを設定し、コミットしない。JAVA_HOMEは新端末の実在するJDK/JBRディレクトリに設定する。旧端末のパスをそのまま使わない。

```powershell
cd app
.\gradlew.bat testDebugUnitTest assembleDebug
```

connectedDebugAndroidTest、adb install、エミュレータ起動は実行しない。APKはapp/app/build/outputs/apk/debug/app-debug.apkに再生成される。署名済み配布用releaseは未整備。

## クラウドの現在状態と認証

- project: dopadopa / number: 463106527119。
- Cloud Run: dopaguard-api / asia-northeast1、00013-hexへ公開traffic100%、AGENT_BACKEND=engine。
- 公開API: https://dopaguard-api-463106527119.asia-northeast1.run.app
- QAタグ: https://engine-qa---dopaguard-api-p4kp2phc5q-an.a.run.app
- Agent Engine: projects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656。
- Firestore: (default)、Tokyo。Firebase email/password有効。
- SA: dopaguard-api@dopadopa.iam.gserviceaccount.com、dopaguard-engine@dopadopa.iam.gserviceaccount.com。
- 非公開staging bucket: gs://dopadopa-agent-staging。
- 設定: server/cloudrun.engine.env.yaml。その他の構成は[環境文書](../architecture/CLOUD_ENVIRONMENT.md)。

Google Cloud SDKを新端末にインストールし、本人がatomic1017@gmail.comでログインする。認証済み状態はGitに含まれない。

```powershell
gcloud auth login atomic1017@gmail.com
gcloud config set account atomic1017@gmail.com
gcloud config set project dopadopa
gcloud auth list
gcloud run services describe dopaguard-api --project=dopadopa --region=asia-northeast1 --format=json
```

クラウド状態は上記コマンドで再確認し、記録だけから再配置しない。toolsのクラウド検証スクリプトはPATHからgcloud.cmdまたはgcloudを取得する。Google Cloud SDKのbinをPATHへ追加し、リポジトリルートからpython -m tools.<モジュール名>で実行する。旧PC固有のCLIパス編集は不要。アクセストークンは表示・保存しない。旧Firebase CLIの保存認証は期限切れだったため、必要なら本人が再ログインする。

既存Engineへはtools.deploy_engine --updateを使う。新createで重複リソースを作らない。artifacts/privateのpackageはGit対象外で、tools.prepare_engineから再生成する。クラウド検証は一時Authと架空sampleを使うが実リソース・課金を利用する。

## 成功証拠と未解決事項

- サーバー48件、Android単体18件、docs4件が直近成功。22tickets validate正常。
- 公開Engineの観測不足シナリオもinsufficient_data/unknown・区間/転換なしで成功。artifacts/cloud/cloud-api-smoke-unobservable.json。再確認はpython -m tools.verify_cloud_api --scenario unobservable（端末不要）。
- Engine直接分析、認可/削除世代、QAタグと公開URLの分析・訂正・削除は架空データで成功。artifacts/cloud/engine-smoke.json、engine-tag-api-smoke.json、cloud-api-smoke.json。
- 過去のEngine例外ログでsample原文/JWTパターン7件を検出。query例外を機密なしenvelopeへ変換し、更新後の監査132件では検出なし。過去の検出証拠も保持。将来全経路の非出力保証とは扱わない。
- 最後のAndroid連携は分析read timeoutで失敗。固定診断はengine_transport、Cloud Run応答55.545秒。artifacts/tests/android-engine-timeout.xml。
- Android分析read timeoutを55→65秒へ変更しCloud Run60秒より短い不整合を修正。単体18件/APKビルド成功、android-analysis-timeout-build.json。端末上の解消は未確認。
- Engine transport55秒タイムアウトの原因は未特定。クラウド単独再検証は成功したが、断続的な失敗が解消した証拠はない。
- 最新のlearning-shorts公開検証は35.173秒でstatus failed/trace空、固定診断engine_output。cloud-api-smoke-learning-shorts.json。transportとは別の内部失敗分類を調査する必要がある。2一時Authcleanuptrue。
- 内部失敗分類（model_execution/model_deadline/result_validation）のローカルコードを追加済み、サーバー49件PASS。Engine/Cloud Runには未反映。次は同じEngineの--updateとCloud Run QA診断版配置から続ける。
- 実YouTube文言取得、実機E2E、提出動画・提出条件確認は未完了。sample成功で実機ACを完了にしない。
- クラウド内容保持期間/ユーザー別レート制限は未整備。Cloud Run max2はユーザー別制限ではない。

## 次の作業

1. 新端末でGit・クラウド状態と上記ローカル検証を確認する。
2. DG-017でEngine transportの遅延を切り分ける。tools.verify_cloud_apiは開始時刻とリクエスト別時間/HTTP状態を記録する。IDをrouteの{id}へ置換し、本文・トークンは保存しない。artifacts/cloud/cloud-api-smoke.jsonのrequest_timingsを使って、Cloud Loggingの固定failure_stageと照合する。
3. モデル45秒制限、認可・世代境界・同意キャンセルを維持する。推測でcold startを原因と断定しない。
4. 実機/エミュレータを使わず、Android中心の再現手順と提出説明を整備できる。DG-015/016は依存・未達を明記する。
5. 実機テストはユーザーの再開指示後のみ。MVP完成扱いはAC-01〜06の証拠が揃ってから。

この引継ぎ時点で既知の試験session8119/85966、Engine更新、Cloud Buildは終了済み。再利用すべき生存ジョブhandleはない。ローカルvenv、SDK、認証、APK、端末DB、添付ZIP、秘密情報はGit対象外。必要なソース・仕様・匿名化検証証拠はリポジトリに含む。
