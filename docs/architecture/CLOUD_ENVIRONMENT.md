# Cloud environment

## 現在の状態（2026-10-11）

- project dopadopa、管理アカウントatomic1017@gmail.com。Firebase Auth/Firestore/Vertex/Cloud Runは構築・架空データ実検証済み。
- Cloud Run公開トラフィックはdopaguard-api-00013-hex（Engine接続）へ100%切替済み。公開API分析/認可/訂正/削除世代PASS。旧00011失敗原因は未特定。最後のAndroid新経路試験は分析タイムアウトで失敗、クラウド単独再検証PASS。今後エミュレータ不使用、実機は待機。
- Agent Engine8372512764677062656はログ対策更新済み。sample分析・本人認可・旧世代拒否を実検証済み。過去stderr漏出7件を記録、最新検証開始以降132件では原文/JWTパターンなし。ログ再監査証拠engine-log-audit-current.json、全将来応答の保証ではない。
- APIビルド5c63bde4-a85d-4efd-9a71-53c1cd52580e SUCCESS。QAタグと通常公開URLでAPI受入PASS。Android経路はタイムアウト失敗、端末連携未確認。
- WebデモはMVP対象外。既存環境は参考成果として保持。

## 構築履歴

以下は当時の状態を残した時系列記録。現在の状態は上記とprocess/HANDOFF.mdを参照する。

- 指定アカウント: atomic1017@gmail.com
- 表示名: dopadopa
- GCP project ID: dopadopa
- Project number: 463106527119
- GCP作成: ACTIVE（2026-10-11）
- Firebase追加: ACTIVE。Management APIの取得結果でprojectId/displayNameがdopadopaであることを確認。証拠はartifacts/cloud/firebase-enrollment.json。
- Firebase CLIの保存済み認証は期限切れ。gcloudの指定アカウントによるOAuth認証をメモリ内で取得し、Firebase Management APIを使用。トークンをファイル・ログへ保存しない。
- .firebasercのdefaultはdopadopa。
- Cloud Run / Firestore / Authentication / Vertex AI / Agent Engineのアプリ基盤構築は未実施。Agent Engine構成についての直前の確認を継続する。
- 請求先01CB99-40818C-4ECC00へのdopadopa関連付けはFAILED_PRECONDITION / Cloud billing quota exceeded。既存関連付けはjp-contents-matome、naminami-27708、silent-ai-chat、pde-calendar、my-project-1510668854300の5件（すべてbillingEnabled=true、指定アカウントでCLI確認）。既存プロジェクトの解除は行っていない。
- ローカルADK経路はserver/adk_agent.py。28件のサーバーテストはPASS。実クラウド接続の証拠ではない。
- 2026-10-11: ユーザーの明示指示でsilent-ai-chatの請求先関連付けを解除。CLI応答billingAccountName空、billingEnabled=falseを確認。その後dopadopaを01CB99-40818C-4ECC00へ関連付け成功、billingEnabled=true。請求先の上限ブロッカーは解消。
- 2026-10-11: Cloud Run/Cloud Build/Artifact Registry/Firestore/Identity Toolkit/Vertex AI/Secret Manager API有効化成功。Firestore (default)をasia-northeast1、Native STANDARDで作成済み。artifacts/cloud/firestore-smoke.jsonの実Store検証5項目PASS、一時架空データは削除済み。Cloud Run/認証アプリ/Agent Engine配置はまだ未実施。
- Cloud Run用サービスアカウントdopaguard-api@dopadopa.iam.gserviceaccount.comを作成。roles/datastore.user、roles/firebaseauth.viewer、roles/aiplatform.userを付与。Artifact Registry dopaguardをasia-northeast1に作成。
- Cloud Build 26482f90-afbe-4cb8-8060-27b1d328547eを開始。最新確認WORKING。コンテナ配置成功は未確認。送信対象は.gcloudignoreでserver/とdocs/samples/およびビルド定義に限定（185.8KiB、68ファイル）。
- 更新: 同BuildはSUCCESS。Cloud Run dopaguard-api revision dopaguard-api-00001-j7gへ配置成功、100% traffic。https://dopaguard-api-463106527119.asia-northeast1.run.app。sample GET 200、無認証session GET 401。実GeminiはToolを呼ぶが分析完了は未達。Agent Engineは未配置。
- 最新: Build04f0ff0c-bce4-48ae-9a2f-393f1b41c9e3 SUCCESS、revision00002-rzh配置、100%。Firebase email/password有効、Web app登録済み。実APIで認証/別所有者拒否/Vertex分析/訂正/削除PASS、artifacts/cloud/cloud-api-smoke.json参照。Agent Engine・Android同期・Web UIは未完了。
- Firebase Android app: 1:463106527119:android:bec70e43d1f1dc528785fa、package com.example.dopadopa。公開client resourcesはapp/app/src/main/res/values/firebase.xml。Auth SDK/同期UIコードのビルドと18単体/5emulator試験はPASS。Android実認証/同期は未検証。
