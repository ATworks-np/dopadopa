# データ契約・Agent Tool仕様（MVP）

## Android InteractionEvent

```json
{
  "event_id": "e0001",
  "session_id": "s0001",
  "app_id": "youtube",
  "package_name": "com.google.android.youtube",
  "timestamp": "2026-10-11T21:08:20+09:00",
  "event_type": "CLICK",
  "text": "Kotlin Coroutine解説",
  "text_status": "available",
  "source": "parent_context",
  "observability": "inferred",
  "data_origin": "real",
  "consent_revision": "consent-v1"
}
```

- `event_type`: `CLICK | SCROLL | WINDOW_CHANGE | APP_FOREGROUND | APP_BACKGROUND` をMVPの共通語彙とする。
- `text_status`: `available | missing | redacted`。`missing`/`redacted` の場合 `text=null`。
- `source`: `clicked_node | descendant | parent_context | none`。
- `observability`: `direct | inferred | unavailable`。取得の確かさでありLLMの確信度ではない。
- `data_origin`: `real | sample`。サンプルデータを本物の操作ログとして表示しない。
- `timestamp`: ISO 8601 + タイムゾーン。期間比較はサーバーでUTC正規化。
- `session_id`, `event_id` は端末内生成、セッション内の順序は安定させる。
- 動画タイトルはクリック要素から得られないことがある。取得元を明記する。未取得は補完しない。

## API案（暫定）

| Method | Path | 説明 |
|---|---|---|
| `POST` | `/v1/sessions:batch` | 同意済みイベントをバッチ登録（認証必須） |
| `POST` | `/v1/analyses` | `session_id` を指定して解析開始 |
| `GET` | `/v1/analyses/{analysis_id}` | 結果と状態を取得 |
| `POST` | `/v1/analyses/{analysis_id}/feedback` | 本人による評価・訂正 |
| `GET` | `/v1/demo/sessions` | サンプルまたは許可済み実データをデモ表示 |

## Gemini Function Tools

| Tool | 入力 | 出力・制約 |
|---|---|---|
| `get_user_goals` | `user_id` はセッション認証から決定 | 本人の確認済みゴールだけを返す |
| `get_timeline` | `session_id`, `limit`, `cursor` | event ID/時刻/イベント種別/欠損状態を返す |
| `get_click_events` | `session_id`, `limit`, `cursor` | クリックの公開可能テキスト、source、observability |
| `get_past_feedback` | `session_id` またはユーザーの過去範囲 | 本人が明示的に評価した情報のみ |
| `save_behavior_feedback` | **Agentには自動書込み許可しない** | 本人操作の認証済みAPI経由で保存 |

サーバーは認証コンテキストから user_id を決め、LLMが自由入力したuser_idで他人のデータを参照させない。各Toolには引数バリデーション、件数上限、タイムアウトを設ける。

## BehaviorAnalysis（返却JSON契約）

```json
{
  "analysis_id": "a0001",
  "session_id": "s0001",
  "segments": [
    {
      "from_event_id": "e0001",
      "to_event_id": "e0003",
      "topic": "Android開発",
      "intent_hypothesis": "技術調査の可能性",
      "evidence_ids": ["e0001", "e0002"]
    }
  ],
  "transitions": [
    {
      "at_event_id": "e0004",
      "hypothesis": "技術調査から別の閲覧行動への移行候補",
      "evidence_ids": ["e0003", "e0004"]
    }
  ],
  "goal_alignment": "possibly_diverged",
  "uncertainty": "意図的な休憩か判断できない",
  "user_confirmation_required": true,
  "data_origin": "sample"
}
```

- `evidence_ids` は入力セッションに存在するイベントIDのみ許可（Validatorが検証）。
- `goal_alignment`: `aligned | possibly_diverged | unknown`。
- 証拠が不足する場合、`unknown` と説明を返す。架空のイベント・閲読時間・視線情報を生成しない。
- スキーマは実装開始時にJSON Schema / Pydantic等で固定してもよい。

## ローカルストレージ案

`Session`, `InteractionEvent`, `UserGoal`, `BehaviorAnalysis`, `BehaviorFeedback`, `ConsentRecord`。分析テーブルと観測データは独立させる。生テキストは保護された保存領域、保存期間の最小化、削除操作に対応。

## 通信断

端末では保存継続、同期停止。分析状態は `pending | running | completed | failed | insufficient_data`。分析失敗時もYouTube操作へ割り込まない。

## 同期要求の削除世代
- 認証GET /v1/user/generationから現在のgenerationを取得し、POST /v1/sessions:batchとsample importにX-DopaGuard-Generationヘッダーで送る。
- 削除完了/開始によりgenerationを増やす。古い世代で形成された要求が削除後に初めて到着しても409で拒否。保存transactionも同じexpected_generationを再検証する。
- 省略ヘッダーは初期世代0のみ許可。削除後の旧クライアントは409となり、新しいプロトコル対応が必要。今回のAPI/Android/Web変更は未配置段階。
- 世代取得後もクライアント側の同意revision/アカウント/cancel境界を検証する。新しい明示同期は新世代を取得できるが、未同意の過去イベントを送る許可にはならない。
- 世代protocolはCloud Run00009-jd9/Android/Webへ反映済み。実APIとAndroidの保留batch試験はartifacts/cloud/sync-generation-smoke.json、artifacts/tests/android-sync-generation.xml。
