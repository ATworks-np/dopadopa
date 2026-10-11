# DopaGuard — 開発ドキュメント・チケット管理

> 対象: 第5回 Zenn AIハッカソン向けMVP / 要件定義 v0.2 / 2026-10-11

## 目標
Android上で取得可能な **YouTube のクリックテキストと操作の時系列** を分析し、Geminiの**行動理解エージェント**が、利用話題・意図の仮説と転換点を**根拠イベントID付き**で提示し、ユーザーが確認・訂正できること。

**MVPの主役はアプリ制限ではなく行動理解。** YouTubeのみ選択可能なのはオンボーディングのUI上のスコープであり、バックエンド、イベントスキーマ、Agentはアプリ非依存とする。

## ディレクトリの使い方

```text
docs/
├── README.md                        ← まず読む
├── AGENTS.md                        ← 開発AI/コーディングエージェント向け必須指示
├── requirements/
│   ├── DopaGuard_要件定義書_v0.2.docx  ← 原本（正）
│   └── TRACEABILITY.md               ← FR / ON / AC とチケットの対応
├── architecture/
│   ├── OVERVIEW.md                   ← 主要構成・実行フロー
│   └── CONTRACTS.md                  ← イベント、Agent Tool、分析出力、API契約
├── process/
│   ├── DEVELOPMENT_PROCESS.md        ← 開発プロセス管理指示書
│   ├── IMPLEMENTATION_PLAN.md        ← 実装順と依存関係・ゲート
│   ├── DEFINITION_OF_DONE.md         ← 完了基準、テスト、エビデンス
│   ├── RISKS_AND_DECISIONS.md        ← リスク・意思決定ログ
│   └── HANDOFF.md                    ← 次の作業者への引継ぎテンプレート
├── tickets/
│   ├── README.md                     ← チケット操作マニュアル
│   ├── BOARD.md                      ← 自動生成された看板
│   ├── backlog/ ready/ in-progress/ blocked/ review/ done/
│   └── ... DG-xxx.md                ← ファイル1枚＝チケット1件
├── templates/
│   ├── TICKET.md
│   └── PULL_REQUEST.md
├── samples/
│   ├── sample_timeline.json          ← 架空データ
│   └── sample_analysis.json          ← 架空の分析結果
└── scripts/
    ├── tickets.py                    ← ファイルベースチケット管理ツール
    └── test_tickets.py               ← スクリプトの自己テスト
```

## まず実行するコマンド

プロジェクトルートにこの `docs/` を配置してから実行する想定です。Python 3.10+ の標準ライブラリだけで動作します。

```bash
python docs/scripts/tickets.py list
python docs/scripts/tickets.py next
python docs/scripts/tickets.py show DG-001
python docs/scripts/tickets.py move DG-001 in-progress --owner YOUR_NAME
python docs/scripts/tickets.py board
python -m unittest discover -s docs/scripts -p 'test_*.py'
```

## 最初の着手順

1. **DG-001 + DG-004**：実機のイベント・クリック文字列の取得可能性を確認し、取得率・欠損を記録（最優先リスクの検証）。
2. **DG-002 + DG-003**：データ契約・ローカルDB・収集対象と同意の境界を定める。
3. **DG-005〜008**：YouTubeのセッション収集、UIイベント取得、タイムライン化。
4. **DG-009〜012**：Cloud Run、Tool Calling、Gemini分析出力、訂正記録。
5. **DG-013、DG-015、DG-016**：Android結果UI・本人訂正・E2E確認と提出素材。Webデモ（DG-014 / FR-09 / AC-07）は2026-10-11ユーザー指示によりMVP対象外。既存成果は参考として保持。

並列着手可否は各チケットの `depends_on` を参照。各タスクの作業終了時にエビデンスを記録し、状態を更新すること。

## P0以外はMVPの完成条件ではない

- **P1**：コンテンツ表示時間（露出時間）、通知/介入、改善提案。
- **P2**：自律学習・自動ポリシー更新、複数アプリ選択解禁。
- 開発中にP0が進まない場合はP1/P2へ手を広げない。

## 正本と整合性

- 要件の正本: `requirements/DopaGuard_要件定義書_v0.2.docx`（本ZIP同梱）。
- チケットはその実装計画。矛盾を見つけたら `process/RISKS_AND_DECISIONS.md` に記録し、仕様を変更する場合は文書を改訂する。
- チケット状態の正本は各 `.md` の `status` と所属ディレクトリ。`tickets/BOARD.md` は生成物。
