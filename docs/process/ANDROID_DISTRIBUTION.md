# Android APK配布

Firebase App Distributionのdopadopa / com.example.dopadopaを使用する。配布済み状態とhashはartifacts/cloud/app-distribution.json。LAN外の端末でもAPKのダウンロードは可能で、本人がインストールする。ADB操作は行わない。

```powershell
# 本人が明示指定した宛先だけへアップロード・招待
python -m tools.distribute_android --tester <本人指定メールアドレス>
# 記録済みuploadを確認（直リンクは出力しない）
python -m tools.distribute_android --check
# 本人が明示指定した宛先へ招待する場合のみ
python -m tools.distribute_android --check --tester <本人指定メールアドレス>
```

**招待制のみ。直ダウンロードリンクの提供は禁止。** Google Cloud SDKにatomic1017@gmail.comでログイン済みであること。OAuthはメモリ内、quota projectはdopadopaへ固定。binaryDownloadUriは出力・保存・共有しない。testing_uriは招待されたテスターのアクセス権が必要。

2026-10-12: 旧release 5lr8509kenbe8を削除しGET 404を確認。新release 4t4qnpquf0m08に同じAPKをアップロードし、本人指定atomic1017@gmail.comだけへのdistribute API成功を確認。メール到着・招待承認・端末インストールは未確認。

APKはdebug署名。端末の既存アプリと署名鍵が違えば上書き更新できない。データ保持のため、更新失敗を自動アンインストールで解決しない。今の配布結果は端末上のインストールやUI動作成功を証明しない。

公式手順: https://firebase.google.com/docs/app-distribution/android/distribute-cli
