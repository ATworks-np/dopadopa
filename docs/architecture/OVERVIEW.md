# MVPアーキテクチャ

```mermaid
flowchart LR
    A[YouTube / Android] --> B[UsageStats + Accessibility]
    B --> C[Privacy Filter]
    C --> D[Event Normalizer + Sessionizer]
    D --> E[(Room / Local Timeline)]
    E --> F[Consent-gated Sync]
    F --> G[Cloud Run API]
    G --> H[(Firestore)]
    I[Cloud Run Agent Runner] --> H
    I <-->|Function calling| J[Gemini]
    I --> K[BehaviorAnalysis JSON]
    K --> L[Review UI / Web Demo]
    L --> M[User Feedback]
    M --> G
```

## ランタイム境界

- **端末側**：セッション開始/終了・イベント捕捉・文言取得可否・機密除外・タイムライン作成。Accessibility callbackは軽く保ち、非同期で保存する。
- **クラウド側**：同意済みイベントに限って受信/保管。GeminiがToolで証拠を取得し、根拠付き仮説を出す。
- **Agent Runner**：Geminiのfunction callsをループ処理。ツールはサーバー側にあり、セッションIDと認可ユーザーでスコープ制限する。
- **UI**：初回Goal/YouTube選択、イベントタイムライン、セグメント説明、根拠、確認/訂正。Webデモのsample/real表示は必須。

## 典型的な分析シーケンス

```mermaid
sequenceDiagram
    participant U as User
    participant A as Android
    participant API as Cloud Run
    participant G as Gemini Agent
    participant DB as Store
    U->>A: YouTubeを操作
    A->>A: UIイベント捕捉・機密除外
    A->>DB: ローカルセッション保存
    U->>A: クラウド解析を許可済み
    A->>API: 同意済みイベント要約
    API->>DB: 分析用データ保存
    API->>G: analyze(session_id)
    G->>API: get_user_goals / get_timeline / get_click_events
    API->>DB: 認可範囲で読み出し
    DB-->>API: 対象セッションの証拠
    API-->>G: Tool results
    G-->>API: BehaviorAnalysis JSON
    API-->>A: 分析結果
    A-->>U: 転換点・根拠を表示
    U->>A: 合っている/違う/分からない
    A->>API: フィードバック保存（本人操作）
```

## 決定事項

- Geminiはタップ検出器ではない。Androidが観測したイベントを**時系列の証拠**として扱う。
- ゴールは本人が確認した内容を正とし、AIの観測仮説で勝手に書き換えない。
- 介入エンジンはP1。今回のMVPの完成条件に含めない。
- 仕様上の未決事項は `process/RISKS_AND_DECISIONS.md` に記録する。
