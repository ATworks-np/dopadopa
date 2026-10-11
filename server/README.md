# Cloud API / Gemini runner

DG-009〜011の初期実装。Firestore・Firebase Auth・Gemini実接続とCloud Runデプロイは未検証。

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
- 分析は同期実行、最大6反復/12Tool/45秒、モデルのHTTPタイムアウト10秒。例外はfailedと匿名化Tool traceに変換。
- Toolは4つのread-onlyのみ。書込Toolは存在せず、訂正は認証済み本人操作のAPIから行う。
- `DELETE /v1/user/data` は本人のデータのみ削除。処理失敗時は成功表示を返さない。

## 未完了事項

- クラウドデプロイ、Firestore実接続、Gemini実応答の証拠取得。
- Android同期・認証UI、同意撤回との競合対策、クラウド保持期間、リクエスト頻度制限。
- Webデモ・サンプル登録導線。現時点で実データを公開する経路はない。
- API testsはMemoryStore、Agent testsはFakeClient。Gemini実行の成功とは扱わない。

## ADK実行経路

`AGENT_BACKEND=adk` でADK 2.11.0の調査Agentと構造化出力Agentを順に実行する。
認証済み所有者とセッションをToolに固定し、必須3Tool、12Tool呼出上限、8モデル呼出上限、45秒の全体期限を適用する。
一時ADKセッションは終了時に破棄する。出力は既存の根拠・欠損・所有者Validatorを通す。
Vertex経由では `GOOGLE_GENAI_USE_VERTEXAI=true` とproject/locationを設定する。
Agent Engineへの配置は未実施。ローカルテストは実ADK Runnerに模擬モデルを渡したもので、実Geminiの応答ではない。
`requirements.lock.txt` はADKとAgent Engine SDKを含む固定済み依存。pip checkとサーバー28件はPASS。

参照: [Google Gen AI SDK](https://github.com/googleapis/python-genai)、[Function calling](https://ai.google.dev/gemini-api/docs/function-calling)、[Firestore認証](https://docs.cloud.google.com/firestore/native/docs/authentication)。
