# MVPアーキテクチャ

## v0.3目標構成（開発中）

現行要件: requirements/REQUIREMENTS_v0.3.md。旧手動導線は下記に保持する。自動ルートは未接続。

```mermaid
flowchart LR
    Y[YouTube利用中] --> C[端末収集・機密除外]
    C --> P[同意/文脈/頻度Policy]
    P --> S[不変snapshot API]
    S --> A[Agent Tool調査/仮説]
    A --> V[根拠/鮮度/現アプリ検証]
    V --> O[overlay提案]
    O --> U[本人が選択]
    U -->|休止を承認| R[10分休止/即時解除]
    U -->|続行/訂正/停止| F[本人選択記録]
```

端末は観測の変化で有界Agentを起動する。モデルは継続監視や制限実行を直接行わない。旧セッション凍結同期を変更せず、現在の観測は別snapshot契約で送信する。本人確認済みゴール/owner/同意revision/削除世代/文脈sequenceを結果と照合する。詳細と実装依存はDG-301〜308を参照。

## v0.2から継承する既存構成

```mermaid
flowchart LR
    A[YouTube / Android] --> B[UsageStats + Accessibility]
    B --> C[Privacy Filter]
    C --> D[Event Normalizer + Sessionizer]
    D --> E[(Room / Local Timeline)]
    E --> F[Consent-gated Sync]
    F --> G[Cloud Run API]
    G --> H[(Firestore)]
    G -->|サービス認証 + Firebase token| I[Agent Engine / ADK]
    I -->|本人認可を再検証| H
    I <-->|Function calling| J[Gemini]
    I --> K[BehaviorAnalysis JSON]
    K --> G
    G --> L[Android根拠・訂正UI]
    L --> M[User Feedback]
    M --> G
```

## ランタイム境界

- **端末側**：セッション開始/終了・イベント捕捉・文言取得可否・機密除外・タイムライン作成。Accessibility callbackは軽く保ち、非同期で保存する。
- **クラウド側**：同意済みイベントに限って受信/保管。GeminiがToolで証拠を取得し、根拠付き仮説を出す。
- **Agent Runner**：現在はAgent Engine上のADK。Geminiのfunction callsを処理し、ToolはセッションIDと認可ユーザーでスコープ制限する。Cloud Run側でも結果のID/origin/evidence/削除世代を再検証する。
- **UI**：Androidの初回Goal/YouTube選択、イベントタイムライン、セグメント説明、根拠、確認/訂正。sample/realを区別する。WebデモはMVP対象外。

## 典型的な分析シーケンス

```mermaid
sequenceDiagram
    participant U as User
    participant A as Android
    participant API as Cloud Run
    participant E as Agent Engine / ADK
    participant G as Vertex Gemini
    participant DB as Store
    U->>A: YouTubeを操作
    A->>A: UIイベント捕捉・機密除外
    A->>DB: ローカルセッション保存
    U->>A: クラウド解析を許可済み
    A->>API: 同意済みイベント要約
    API->>DB: 分析用データ保存
    API->>E: Firebase token + session_id + 削除世代
    E->>E: 本人認可と削除世代を再検証
    E->>G: 証拠調査の指示
    G->>E: get_user_goals / get_timeline / get_click_events
    E->>DB: 認可範囲で読み出し
    DB-->>E: 対象セッションの証拠
    E-->>G: Tool results
    G-->>E: BehaviorAnalysis JSON
    E-->>API: 分析結果と機密なしTool trace
    API->>API: ID/origin/evidence/削除世代検証
    API-->>A: 分析結果
    A-->>U: 転換点・根拠を表示
    U->>A: 合っている/違う/分からない
    A->>API: フィードバック保存（本人操作）
```

## 決定事項

- Geminiはタップ検出器ではない。Androidが観測したイベントを**時系列の証拠**として扱う。
- ゴールは本人が確認した内容を正とし、AIの観測仮説で勝手に書き換えない。
- v0.3では自動提案と本人承認による休止をP0へ変更。未承認の制限は行わない。
- 仕様上の未決事項は `process/RISKS_AND_DECISIONS.md` に記録する。
