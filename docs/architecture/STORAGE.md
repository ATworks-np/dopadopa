# 保存・同意・削除契約（DG-002）

- Room v1: Session / InteractionEvent / UserGoal / ConsentRecord / BehaviorAnalysis / BehaviorFeedback。
- イベントのIDはUUID、タイムスタンプはUTC epoch milliseconds。通信時はISO 8601 UTCに変換。
- イベントはtimestamp、eventIdの順。sessionIdの外部キーと複合インデックスを持つ。
- 観測と分析は別テーブル。missing/redactedの原文はnull。入力欄は取得しない。
- appId/packageNameを維持。サンプル・実機のdataOriginは別値で保持する。
- DBはアプリ専用領域、OSの保護下に保存。バックアップ禁止。生データをLogcatに出さない。
- 保存期間は7日。収集/画面読出し時に期限切れセッション・分析・訂正を削除する（後続Repositoryで接続）。
- 同意は端末内収集とクラウド送信を別管理。クラウド未同意時はキュー送信しない。
- 撤回は新規取得と保留中送信を停止する。削除操作は観測、分析、訂正、ゴール、同意を一括削除。
- 既にクラウドへ送ったデータは認証済み削除APIで別途削除する。通信失敗時に削除完了とは表示しない。
- v1で既存DBなし。v2以降は明示migrationとmigrationテスト必須。破壊的migrationは禁止。
- DG-001のJSONLは暫定スパイク。正式Repository統合時に削除し、無断移行・無断送信しない。

## Room v3 同期固定データ
sync_envelopesは本人UID/sessionのqueueKeyと同意revisionで最初の送信JSONを固定する。再送候補で上書きしない。機密を含み得るためアプリ専用DB内に保存し、SharedPreferences/ログ/バックアップへ原文を書かない。sessionsへのFK cascadeで7日保存期間と削除に連動する。クラウド/端末削除で固定データも消去する。v1→v2→v3 migrationを使用する。

固定後に遅延して追加された観測は端末タイムラインへ残り、クラウドimmutableセッションへ混ぜない。「同期後の追加観測（端末のみ）」として件数を通知する。実転送中の再試行・遅延観測のクラウド試験は別途必要。
