# イベントと行動理解の現状（2026-10-12）

クリック収集は残っている。画面タイトルの取得を追加しており、クリック通知への依存をなくす。

| event_type | 取得元 | textの扱い | 判断上の意味 |
|---|---|---|---|
| CLICK | TYPE_VIEW_CLICKED | sourceノード/子孫/項目内文脈、取得不可はmissing | クリック通知が観測された。視聴/注視は不明 |
| WINDOW_CHANGE | TYPE_WINDOW_STATE_CHANGED、または内容変更時に取得したタイトルの変化 | 一般変更はmissing。タイトル取得時はavailable/parent_context/inferred | 画面に関連するタイトルが表示された。クリックした証拠ではない |
| SCROLL | TYPE_VIEW_SCROLLED | missing、500ms重複抑止 | スクロールの操作時刻。コンテンツ内容を示さない |
| APP_FOREGROUND | UsageStats ACTIVITY_RESUMED | missing | 観測された利用開始 |
| APP_BACKGROUND | UsageStats ACTIVITY_PAUSED/画面OFF | missing | 観測された離脱。終了未観測なら終了を作らない |

## エージェントへ渡す情報

- get_user_goals: 本人確認済みの目標。
- get_timeline: 全5種の時系列、ID、取得状態、source/observability。textは含まない。
- get_content_events: CLICK/WINDOW_CHANGEのテキストと取得状態、event_type、source/observability。必要な範囲をページングする。
- get_past_feedback: 本人が明示した過去の回答。

以前のget_click_eventsはCLICKだけを返し、画面タイトルがモデルへ届かなかった。新エージェントはget_content_eventsを使用する。API/Androidは既存traceの旧Tool名も読めるが、名前を変更して取得事実を偽装する処理はない。

## 判断と構成

1つのLlmAgent dopaguard_behaviorがTool調査と構造化出力を行う。調査/提示を別エージェントへ引き継がない。1回の分析でモデルを複数回呼ぶ場合がある。最大8モデル呼出、12Tool、45秒。

文字ありCLICKまたはWINDOW_CHANGEを内容根拠として利用できる。文字なし変更/スクロール/開始/離脱のみなら内容の判断はunknown。表示タイトルを根拠とした説明は「表示された」とし、クリック/視聴を観測したとは書かない。missing/redactedを補完しない。画面変更の関連づけはinferredのまま維持する。

入力欄/認証/機密UIを除外する。本人認可・世代・同意・event_id照合はエージェント外のValidatorで強制する。過去の試作版で収集されたおすすめカード/評価数の誤取得履歴は、正しいタイトルの証拠とみなさない。

## 2026-10-12 コンテンツ移動の連鎖検知（DG-303）
本人指示で、ゴールとの一致と、ついつい次の関連コンテンツへ移る連鎖の可能性を独立に調べる。内容が学習/情報収集/娯楽のゴールに合う場合でも、複数の具体的な表示タイトルと操作時系列が連鎖を支持するなら休止を提案する。goal_alignmentは内容と本人ゴールの対応のまま、usage_pattern=possible_chaining/no_clear_chaining/unknownとusage_pattern_evidence_idsを追加。連鎖根拠は少なくとも3つの異なる取得済みコンテンツ、最大10件。同じタイトルのCLICK/WINDOW_CHANGEを二重計数しない。
時間だけ/SCROLLだけ/単発/文字欠損は連鎖の根拠にしない。時刻は具体的なコンテンツ切り替え頻度の補助として使う。系統立った講義/目的のある比較や深掘りとの区別を検討する。関連動画リンクを経由したか/自動再生/実際の視聴/本人の無意識や依存は未観測であり断定しない。「ドパガキ」という呼称を本人へ表示しない。本人への提案は続ける/休むで承認を委ねる。
