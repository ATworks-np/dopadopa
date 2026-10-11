# MVP受入状況（2026-10-11）

## 現在の部分受入（2026-10-11 19:15 JST）

実機realデータの自動Agent分析と必須3Tool、unknown返却、待機中/判定中の状態アイコン、固定段階の分析ログを確認。これをRA全体の合格とは扱わない。休止提案/本人承認休止/解除/永続復元と一連の撤回・再起動試験は未完了。詳細と証拠は [最新引き継ぎ](HANDOFF_2026-10-12_CURRENT.md)。以下の「未接続/実機保留」は過去の判定。


## v0.3への改訂

現行要件はrequirements/REQUIREMENTS_v0.3.md。利用中の自動分析・overlay提案・本人承認休止を新P0へ追加した。RA-01〜09は未完了、DG-300〜308で検証する。以下は旧ACの履歴であり、新MVPの合格表ではない。

本チャットで実機試験を再開。Pixel 9a実データの手動同期8セッション、分析2件、クリック根拠表示は成功（artifacts/tests/android-real-device-debug.json）。自動起動/overlay/休止の成功証拠はまだない。

MVP全体は未完了。ユーザー指示により実機試験は再開指示まで待機する。Webデモ（FR-09 / AC-07）はMVP対象外。以下は保存済み証拠の確認結果であり、新たな実機試験を実施した記録ではない。

追加方針: ユーザー指示により今後エミュレータは使用しない。過去のエミュレータ結果は証拠として保持する。Engine公開経路は架空API検証PASSだが、最後のAndroid連携試験は分析待機タイムアウトで失敗（android-engine-timeout.xml）。端末連携の成功は未確認。

| 条件 | 現在の判定 | 確認できた範囲と証拠 | 残る確認 |
|---|---|---|---|
| AC-01 YouTube限定・汎用app_id | エミュレータ合格、実機未実施 | [DG-003](../tickets/done/DG-003.md)、OnboardingTestで選択制限・ゴール確認・同意分離を検証 | 本人端末の初回設定で同じ制限を確認 |
| AC-02 実機イベント保存・欠損表示 | 未実施 | [同期試験](../../artifacts/tests/android-frozen-retry-overflow.xml)は架空sample。実Firebase/Firestoreで同期できることの証拠であり、YouTube収集証拠ではない | 実YouTubeの開始/終了/クリック/欠損をsession_id/event_idで保存・表示 |
| AC-03 実機クリック文言1例 | 未実施 | 実機由来の文言取得証拠なし。親ノード抽出は無効 | 公開動画の本人操作と取得文言を照合。取得不能ならmissingと原因を記録し未達とする |
| AC-04 Tool調査・話題/転換候補 | サンプル合格、実機E2E未実施 | [実Gemini結果](../../artifacts/cloud/gemini-smoke.json)、[根拠監査](../../artifacts/cloud/analysis-evidence-audit.json)。3シナリオの必須Toolと根拠・unknownを確認 | 実機同期セッションで同じ分析経路を確認。モデルの全応答の意味的正しさを保証した証拠ではない |
| AC-05 根拠event_id・不明UI | エミュレータ合格、実機未実施 | [Android分析UI試験](../../artifacts/tests/android-analysis-ui.xml)のhypothesisOpensObservedEvidence / insufficientObservationsStayUnknown。混在XML内のクラウド試験1件はskipなのでその成功証拠にはしない | 実機で同期元イベントと根拠画面を照合 |
| AC-06 本人評価・訂正保存 | サンプル合格、実機E2E未実施 | [訂正UI/クラウド試験](../../artifacts/tests/android-feedback-cloud.xml)、[最新同期試験](../../artifacts/tests/android-frozen-retry-overflow.xml)。明示回答・incorrect訂正文・unknown・履歴・観測不変を検証 | 本人端末で分析→訂正→再表示を通して確認 |
| AC-07 Webデモ | MVP対象外 | [スコープ変更](../requirements/TRACEABILITY.md) | 必須受入・依存に含めない |

## 実機試験再開後の手順

1. ユーザーの再開指示を確認してから端末を接続し、OS/機種/アプリ版と権限設定を記録する。
2. YouTube限定選択、ゴール確認、収集とクラウドの別同意を確認する。未同意の過去観測を送信しない。
3. 公開動画でホーム/検索結果/動画詳細/Shortsを操作する。検索入力欄・認証・DM等は対象外。実際の閲読や注視は取得した事実にしない。
4. 時系列・ID・source・observability・text_statusを本人が画面と照合する。原文を不要なログやリポジトリに載せず、証拠は最小限に匿名化する。
5. 明示同期→Gemini調査→根拠画面→本人回答保存→再表示を確認する。観測イベントが訂正で変わらないことも確認する。
6. 撤回/ログアウト/クラウド削除/端末削除を確認し、各ACを合格・未達・未実施で更新する。取得不能な文字列を補完しない。

## 実機を待たずに進める項目

- Agent Engineは配置済み、Cloud Run公開00013-hexからの架空分析/認可/訂正/削除はPASS。Android連携でengine_transport失敗を検出し、端末を使わずクラウド単独でタイムアウトの再現性を確認する。
- Android中心の再現手順・アーキテクチャ・提出説明資料を整備する。外部提出条件は提出前に公式情報で確認する。
- DG-015の最終受入は実機試験を待つ。サンプル成功でAC-02/03を合格に変更しない。
