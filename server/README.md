# Cloud API / Gemini runner

Firebase Auth・Firestore・Vertex Geminiの実接続とCloud Run配置を検証済み。Android実機収集はユーザーの再開指示まで待機中。最新の証拠・未完了事項は [HANDOFF](../docs/process/HANDOFF.md) と [MVP受入表](../docs/process/MVP_ACCEPTANCE_STATUS.md) を参照。

## ローカル

```
python -m venv .venv
.venv/Scripts/python -m pip install -r server/requirements.lock.txt
.venv/Scripts/python -m unittest discover -s server/tests -v
```

`STORE_MODE=memory` はローカルの明示指定時のみ。Cloud Runでは禁止。
API認証はFirebase ID token。テストの依存差し替えは本番コードの認証迂回を作らない。
API実行: `.venv/Scripts/python -m uvicorn server.app:app --port 8080 --no-access-log`。

## クラウド設定

- `GOOGLE_CLOUD_PROJECT`: 利用するプロジェクト。Firestore NativeとFirebase Authを有効にする。
- `GEMINI_MODEL`: 利用可能なGeminiモデルIDを明示指定。
- Vertex AIの場合 `GOOGLE_CLOUD_LOCATION`（既定global）、ADC/サービスアカウントで呼び出す。
- Gemini Developer APIの場合 `GEMINI_API_KEY` をSecret Manager経由で設定。リポジトリに書かない。
- 本番StoreはFirestore。ユーザーIDは検証済みトークンから取得し、ユーザー別subcollectionで分離する。
- Docker: プロジェクトルートをビルドコンテキストに `docker build -f server/Dockerfile .`。
- HTTPリクエストは300KB、イベントは500件。セッションは閉じた後に送る。登録後の内容変更は409、同一再送は冪等。
- ADKの分析は同期実行、8モデル呼出/12Tool/45秒。legacy runnerは最大6反復。例外はfailedと機密を含まないTool traceに変換。
- Toolは4つのread-onlyのみ。書込Toolは存在せず、訂正は認証済み本人操作のAPIから行う。
- `DELETE /v1/user/data` は本人のデータのみ削除。処理失敗時は成功表示を返さない。

## 検証済み範囲と残作業

- 公開Cloud Runはdopaguard-api-00013-hex（Engine接続）へ100%切替済み。公開APIの実認証・所有者分離・Gemini分析・本人訂正・削除・世代境界は架空データでPASS。旧00011-m59の失敗原因は未特定。
- Android同期・認証・分析/訂正UI、同意撤回、削除世代、固定再送、端末7日保持をエミュレータと架空クラウドデータで検証済み。実YouTube収集の証拠ではない。
- Agent Engineの実sample分析と認可境界は検証済み。ログ問題を対策後、最新検証開始以降のruntime132件では3架空シナリオの原文/JWTパターンを検出せず。公開APIの新経路はPASS、最後のAndroid連携試験はengine_transport/read timeoutで失敗。クラウドAPI単独再検証はPASS。今後エミュレータはユーザー指示により使用しない。
- サーバー48件PASS。API単体はMemoryStore、ADK単体は実Runner＋模擬モデル。実Vertex/Firestore/Engine検証の証拠はartifacts/cloud、Androidはartifacts/testsに分離する。
- クラウドの内容保持期間・ユーザー別リクエスト頻度制限は未整備。Cloud Runのmax2/concurrency4はユーザー別レート制限ではない。
- Webデモは既存参考成果で、ユーザー指示によりMVP対象外。実機E2E・提出物は未完了。

## ADK実行経路

`AGENT_BACKEND=adk` でADK 2.11.0の調査Agentと構造化出力Agentを順に実行する。
認証済み所有者とセッションをToolに固定し、必須3Tool、12Tool呼出上限、8モデル呼出上限、45秒の全体期限を適用する。
一時ADKセッションは終了時に破棄する。出力は既存の根拠・欠損・所有者Validatorを通す。
Vertex経由では `GOOGLE_GENAI_USE_VERTEXAI=true` とproject/locationを設定する。
Agent Engineはprojects/463106527119/locations/asia-northeast1/reasoningEngines/8372512764677062656。`tools.deploy_engine --update`で同じresourceを更新する。新規createを繰り返さない。
`AGENT_BACKEND=engine`と`AGENT_ENGINE_RESOURCE`でremote callerを選ぶ。設定候補はcloudrun.engine.env.yaml。EngineはFirebase本人認可を再検証し、結果のID/origin/evidence/世代をCloud Runでも再検証する。
telemetry無効だけでは例外時のruntime stderrに引数が記録されたため、queryを機密なしerror envelopeへ変更した。更新後のsample/拒否試験とログ再監査は通過した。過去の検出記録を残し、今後も機密非出力を検証する。
`requirements.lock.txt` はADKとAgent Engine SDKを含む固定済み依存。ローカル試験と実クラウド試験は別の証拠として扱う。

参照: [Google Gen AI SDK](https://github.com/googleapis/python-genai)、[Function calling](https://ai.google.dev/gemini-api/docs/function-calling)、[Firestore認証](https://docs.cloud.google.com/firestore/native/docs/authentication)。

## 利用中スナップショット（DG-302 / v0.3）

終了済みセッションを変更せず、`POST /v1/live/snapshots` に本人のゴール・goal_revision・consent_revision・3種類の同意・consent_since・captured_at・generation・context_sequenceと最大100イベントを送る。Firebase本人認証と `X-DopaGuard-Generation` を必須の境界として扱う。同一snapshot_idの同じ内容だけ再送可能で、再送で期限は延長しない。300KBの既存入口上限を適用する。

`POST /v1/live/snapshots/{id}/analyze` は当該snapshotだけをAgent Toolへ公開する。取得後60秒以内に開始し、本人別60秒間隔・60回/時・同時1件をFirestoreトランザクションで適用する。失敗も回数へ算入し、端末/クラウドデータ削除で頻度制限をリセットしない。結果はsnapshot/goal/consent/generation/contextへ束縛する。サンプルはsampleのまま扱い、実機介入の証拠にしない。

内容は24時間のexpires_atを持ち、期限後の読み取りをAPI/Toolで拒否する。登録時の期限切れ掃除に加え、snapshots/live_analyses/live_actionsのFirestore TTLを設定する。TTLの物理削除は非同期で、24時間ちょうどの消去を保証しない。

検証: server単体61件、実Firestoreの不変性/原子実行/回数/削除、検証タグの実Agent Engine分析はsampleでPASS。証拠はartifacts/cloud/live-*.json。初回のlive分析はfailed、再試験はcompletedで原因未特定。公開への切替とAndroid自動実行は別の検証を要する。
