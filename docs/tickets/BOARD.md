# DopaGuard チケットボード

> このファイルは `python docs/scripts/tickets.py board` で自動生成。直接編集しない。

合計 **21** 件 / P0 **16** 件

## backlog (6)

| ID | 優先 | マイルストーン | タイトル | 依存 | 担当 |
|---|---|---|---|---|---|
| [DG-016](backlog/DG-016.md) | P0 | M5 | 提出用README・説明資料・デモ動画の準備 | DG-015 | unassigned |
| [DG-101](backlog/DG-101.md) | P1 | Future | コンテンツ表示時間（露出）の推定 | DG-006, DG-007 | unassigned |
| [DG-102](backlog/DG-102.md) | P1 | Future | やさしい通知・続行確認UI | DG-011, DG-013 | unassigned |
| [DG-103](backlog/DG-103.md) | P1 | Future | Policy Agentの改善提案（未適用） | DG-013 | unassigned |
| [DG-201](backlog/DG-201.md) | P2 | Future | 個人最適化・介入効果学習 | DG-102, DG-103 | unassigned |
| [DG-202](backlog/DG-202.md) | P2 | Future | 複数アプリ選択の解禁 | DG-007, DG-201 | unassigned |

## ready (0)

_該当なし_

## in-progress (7)

| ID | 優先 | マイルストーン | タイトル | 依存 | 担当 |
|---|---|---|---|---|---|
| [DG-005](in-progress/DG-005.md) | P0 | M2 | UsageStatsによるYouTubeセッション収集 | DG-002, DG-004 | Codex |
| [DG-006](in-progress/DG-006.md) | P0 | M2 | Accessibilityクリック収集とYouTube App Adapter | DG-001, DG-002, DG-004 | Codex |
| [DG-007](in-progress/DG-007.md) | P0 | M2 | イベント正規化・タイムライン統合 | DG-005, DG-006 | Codex |
| [DG-008](in-progress/DG-008.md) | P0 | M2 | 機密除外・同意管理・安全な同期 | DG-002, DG-003, DG-007 | Codex |
| [DG-012](in-progress/DG-012.md) | P0 | M4 | Android分析結果画面・根拠イベント閲覧 | DG-007, DG-011 | Codex |
| [DG-013](in-progress/DG-013.md) | P0 | M4 | ユーザー確認・訂正とフィードバック保存 | DG-009, DG-012 | Codex |
| [DG-015](in-progress/DG-015.md) | P0 | M5 | AC-01〜AC-06 E2E検証と未達管理 | DG-003, DG-005, DG-006, DG-007, DG-008, DG-011, DG-012, DG-013 | Codex |

## blocked (2)

| ID | 優先 | マイルストーン | タイトル | 依存 | 担当 |
|---|---|---|---|---|---|
| [DG-001](blocked/DG-001.md) | P0 | M2 | YouTube実機イベント取得スパイク | - | Codex |
| [DG-004](blocked/DG-004.md) | P0 | M1 | Androidプロジェクトと最小実機デバッグ基盤 | - | Codex |

## review (0)

_該当なし_

## done (6)

| ID | 優先 | マイルストーン | タイトル | 依存 | 担当 |
|---|---|---|---|---|---|
| [DG-002](done/DG-002.md) | P0 | M1 | データ契約・Roomスキーマと個人情報境界 | - | Codex |
| [DG-003](done/DG-003.md) | P0 | M1 | 初回オンボーディングとYouTube選択画面 | DG-002 | Codex |
| [DG-009](done/DG-009.md) | P0 | M3 | Cloud Run APIとFirestore保存 | DG-002 | Codex |
| [DG-010](done/DG-010.md) | P0 | M3 | Gemini Tool Calling / Agent Runner | DG-009 | Codex |
| [DG-011](done/DG-011.md) | P0 | M3 | Gemini行動理解の構造化出力と根拠検証 | DG-010 | Codex |
| [DG-014](done/DG-014.md) | P0 | M5 | Cloud Run WebデモとAgentトレース可視化 | DG-010, DG-011 | Codex |
