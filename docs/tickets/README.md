# チケット管理ガイド

## データモデル

- `tickets/<status>/DG-xxx.md`: ファイル1枚につき1チケット。
- Front Matterの `id`, `title`, `priority`, `status`, `milestone`, `estimate`, `owner`, `depends_on`, `requirements`, `acceptance`, `updated` が管理対象。
- `status` と所属ディレクトリ名は一致させる。手動移動よりCLIでの更新を推奨。
- `BOARD.md` はスクリプトが生成するため手動編集しない。

## よく使うコマンド

```bash
python docs/scripts/tickets.py list
python docs/scripts/tickets.py list --priority P0
python docs/scripts/tickets.py next
python docs/scripts/tickets.py show DG-001
python docs/scripts/tickets.py move DG-001 ready
python docs/scripts/tickets.py move DG-001 in-progress --owner YOUR_NAME
python docs/scripts/tickets.py move DG-001 blocked
python docs/scripts/tickets.py move DG-001 review
python docs/scripts/tickets.py move DG-001 done
python docs/scripts/tickets.py board
python docs/scripts/tickets.py validate
```

`done` に変更する前に、対象ファイルの `## 受け入れ条件` を `[x]` にして、`## 検証記録` に実行したテストの結果を追記する。未実施のままではCLIが拒否する。

## 依存関係について

- `next` は依存チケットがdoneになっているP0作業を先に表示する。
- `ready`/`in-progress` への移動は依存関係を原則満たす必要がある。例外的に並列で進める場合は別途 `--force` を使い理由を残す。
- 依存関係を満たさない`done`は許可しない。

## チケットの追加

1. `templates/TICKET.md` を `tickets/backlog/DG-XXX.md` にコピーしFront Matterを記載。
2. 依存ID、要件ID、受け入れ条件、検証方法を明確化する。
3. `tickets.py validate` → `tickets.py board` を実行する。
4. P0追加や要件変更は開発プロセス指示書とリスクログに記録する。

## DoDに必要な証拠

プログラムの成功/失敗ログ、実機画面、JSONレスポンス、匿名化再現データ、レビューPRなど。実ログとサンプルログを明記する。機密テキストをチケットに貼り付けない。
