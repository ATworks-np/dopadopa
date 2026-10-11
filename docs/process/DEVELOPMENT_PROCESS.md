# 開発プロセス管理指示書

## 目的・担当範囲

- 本文書を**日々の作業の基準**とする。要件定義の正本は `../requirements/DopaGuard_要件定義書_v0.2.docx`。
- チケットを小さな完成単位に分割して、トレーサビリティ、変更履歴、証拠、未達事項を残す。
- 1チケット1PR/ブランチが原則。例: `feat/DG-006-click-collector`。コミット例: `feat(DG-006): collect YouTube click events`。

## ステータス遷移

`backlog → ready → in-progress → review → done`。障害時はどこからでも `blocked`、解消後に `ready` か `in-progress` へ戻す。`done` 後の再オープンは理由をチケットに追記。

- `backlog`: 着手前、依存が残っている可能性あり。
- `ready`: 依存を満たした・受入条件が明確。
- `in-progress`: 作業中。`owner` をセットする。
- `blocked`: ブロッカーと解除条件を `## ブロッカー` に書く。
- `review`: 実装・テストは済んだがレビュー/受け入れ判定待ち。
- `done`: チェックリストすべて完了、実行した検証と成果物パスを記録済み。

## チケットの操作

```bash
python docs/scripts/tickets.py list --priority P0
python docs/scripts/tickets.py next
python docs/scripts/tickets.py move DG-001 ready
python docs/scripts/tickets.py move DG-001 in-progress --owner YOUR_NAME
python docs/scripts/tickets.py move DG-001 review
# チケット本文の受入条件を [x] にし、検証記録に証拠を書く
python docs/scripts/tickets.py move DG-001 done
python docs/scripts/tickets.py board
python docs/scripts/tickets.py validate
```

`done` への移動は依存チケットと全チェックボックス、検証記録が揃っていなければCLIが拒否する。`tickets/BOARD.md` は編集せず自動生成。

## 標準開発サイクル（毎チケット）

1. **Plan**：チケットの目的・受け入れ条件・関連要件・依存を確認。`ready` への移行可否を判定。
2. **Implement**：実装手順に沿って最小差分を作る。データの欠損・同意を扱うテストを早い段階で追加。
3. **Test**：単体→統合→実機の順で実行し、失敗時の例外と復帰も検証する。
4. **Review**：プライバシー、権限、仮説を事実として扱っていないか、サンプル/実ログの混同がないか確認。
5. **Evidence**：実行コマンド・結果・スクリーンショット/ログパス（機密は伏せる）をチケットに記録。
6. **Close**：チェックボックスを埋め `done` へ移動。対応FR/ACの満たした範囲を確認。

## 日次の運用

- 開始: `tickets.py next` から着手、ブロッカーとクリティカルパスを確認。
- 終了: `tickets.py board` / `validate` を実行、`process/HANDOFF.md` 形式で成果・未決事項を残す。
- P0がブロックされている間はP1を拡大しない。実機で文言が取れない場合、その制約をデモで正直に示す。
- 追加要求はチケット化し、P0スコープ変更は必ずリスク/意思決定ログに記録する。

## 品質ゲート

- Gate 0: 実機でクリック/テキストが取れるか確認（DG-001）。失敗時は実機証拠とfallbackを残す。
- Gate 1: サンプルデータからGemini Tool Callingとevidence_idsが安定する（DG-010, DG-011）。
- Gate 2: Android実機セッション→Gemini解析→訂正がE2Eでつながる（DG-015）。
- Gate 3: Cloud Runデモで実ログとサンプルを明示し、ACを点検して提出する（DG-014, DG-016）。

## 変更管理

- 要件の解釈・セキュリティに影響する変更は `RISKS_AND_DECISIONS.md` へ追記し、関連チケットIDと選択肢/決定理由を残す。
- `requirements/DopaGuard_要件定義書_v0.2.docx` を黙って書き換えない。改訂版は別版として作成し、トレーサビリティを更新する。
- ハッカソン募集要項・締切時刻・提出形式など外部条件は、提出直前に公式ページで必ず再確認する。
