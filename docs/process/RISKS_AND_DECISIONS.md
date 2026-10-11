# リスク・意思決定ログ（ADR）

| ID | 状態 | 論点 / リスク | 対応方針 | 関連 |
|---|---|---|---|---|
| R-01 | Open | YouTubeのAccessibility要素がタイトルを公開しない | DG-001で画面別に実機検証。取得率とmissingを記録 | DG-001, FR-03, AC-03 |
| R-02 | Open | UI変更でテキスト取得ルールが壊れる | App Adapterのバージョン管理、unknownフォールバック | DG-006 |
| R-03 | Open | テキスト中に機密情報が混ざる | 監視対象限定・入力欄/機密UI除外・クラウド同意分離 | DG-002 |
| R-04 | Open | Geminiが根拠のない転換点を生成 | evidence_ids実在検証、unknown返却、UIに不確実性表示 | DG-011 |
| R-05 | Open | Accessibilityの配布/公開要件に抵触 | MVPは研究プロトタイプ・プレイ公開は別審査。最新規定を確認 | DG-016 |
| R-06 | Open | ネットワーク・Geminiが停止 | 端末の収集独立、再試行、failed/insufficient_data | DG-009, DG-010 |
| R-07 | Open | 時間不足で実ログが取れない | サンプルによる分析実証は可能。ただしAC-03未達を明記 | DG-001, DG-015 |

## ADRテンプレート

### ADR-20261011-04: 削除と実行中分析の競合
- 状況: 本人が削除した後、実行中の分析が結果を再保存し得た。
- 決定: owner単位のgeneration/deletingを使い、Firestore書込トランザクション内で削除状態と処理開始時generationを検証する。
- 削除中は別インスタンスの書込も拒否。削除失敗は書込停止を維持し、明示的な再削除を許可する。並行削除は拒否する。
- 証拠: MemoryStore/APIの競合再現テストを含む24件PASS。実Firestoreトランザクションは未検証。
- 影響: DG-009。Agent Engine採用時にもAPI保存時の同じ境界が必要。

### ADR-20261011-01: 実機待ちの独立実装
- 状況: ADBに本人の実機なし。携帯AVDもイメージ不足。Android35タブレットAVDでDB/画面試験は可能。
- 決定: DG-001はblocked。実機受入は未達のまま、確定したデータ契約と模擬応答による独立コード準備を進める。
- 理由: 実機取得の成功を捏造せず、端末接続後にすぐ照合できるようにする。
- 影響: DG-005/006/007とDG-009/010/011を依存未達時はforceでin-progress。理由は各チケットに記録。doneの依存条件は緩めない。
- 検証: 実機AC-02/03、Gemini実Tool Calling AC-04、Cloud Run AC-07は別証拠を取得する。

### ADR-20261011-02: スパイクと本実装の境界
- 決定: DG-001のJSONLは一時的な実機取得スパイク。正式本実装はRoom。親文脈抽出は実機照合まで無効。
- 理由: 未検証の親ノード文字列を動画タイトルとして扱わない。
- 影響: セッション120秒区切りは暫定。DG-005/007でUsageStatsベースに置換。原文はバックアップ禁止・ログ出力禁止。

### ADR-20261011-03: クラウド構成
- 決定: FastAPI/Pydantic、Firestoreの所有者別subcollection、Firebase ID token、google-genaiの明示read-only Toolループ。
- 理由: 観測契約と認可をサーバーで検証し、Agentに書込権限を与えない。
- 未決: 利用Google Cloudプロジェクト/Firebaseアプリ、Geminiモデル・実接続、削除と分析の競合、保持期間、同期と頻度制限。
- 検証: MemoryStore/FakeClientはローカル制御テストのみ。実サービスでの成功とは扱わない。

### ADR-20261011-04: Vertex用schemaと最終検証の分離
- 状況: 実Gemini検証でSDKがboolean Literalを拒否し、詳細なJSON Schema制約を送った要求も400となった。
- 決定: ProviderAnalysisはVertexに渡すboolean/基本schemaを生成。モデルの結果は元のAnalysisで厳密に再検証し、本人確認true・長さ/件数・余分なfield禁止・実在根拠・欠損を維持する。
- 理由: Providerのschema対応範囲に合わせながら、受入契約を緩めない。構造化出力段階にはTool履歴を再送せず、前段の調査結果を明示入力する。
- 検証: 実Vertex/ADKの架空3件completedとサーバー30件PASS。実機・Cloud Run経由の分析は別検証。

```text
### ADR-YYYYMMDD-NN: タイトル
- 状況: 決定の背景
- 選択肢: A / B / C
- 決定: 採用したもの
- 理由: 影響、トレードオフ
- 影響: 対象要件、チケット、マイグレーション
- 検証方法: 成功/失敗を何で判断するか
```

## 2026-10-11: WebデモをMVPから除外
- 決定根拠: ユーザーの明示指示『webデモはmvpから外してください』。
- FR-09 / AC-07 / DG-014をMVPの必須範囲・完成判定から除外。DG-015/016の依存と受入をAndroid中心に変更。
- 既存Webコードと公開サービスは参考成果として保持。Android用Cloud Run API、Firebase、Geminiは引き続き必要。
- v0.2 docxは原本履歴として保持し、TRACEABILITYとDoDの明示改訂を優先。AC-01〜06、実機証拠、同意・所有者境界は維持。
