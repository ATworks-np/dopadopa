# Android APK配布

Firebase App Distributionのdopadopa / com.example.dopadopaを使用する。配布済み状態とhashはartifacts/cloud/app-distribution.json。LAN外の端末でもAPKのダウンロードは可能で、本人がインストールする。ADB操作は行わない。

```powershell
# 現在のAPKをアップロード（同じhashの記録があればoperationを再確認）
python -m tools.distribute_android
# 記録済みuploadを確認し、署名付き直接URLを取得
python -m tools.distribute_android --check
# 本人が明示指定した宛先へ招待する場合のみ
python -m tools.distribute_android --check --tester <本人指定メールアドレス>
```

Google Cloud SDKにatomic1017@gmail.comでログイン済みであること。OAuthはメモリ内、quota projectはdopadopaへ固定。download_urlは署名付きで通常1時間有効、ログ/リポジトリへコピーしない。期限切れ時は--checkで再取得する。testing_uriはテスターのアクセス権が必要。

APKはdebug署名。端末の既存アプリと署名鍵が違えば上書き更新できない。データ保持のため、更新失敗を自動アンインストールで解決しない。今の配布結果は端末上のインストールやUI動作成功を証明しない。

公式手順: https://firebase.google.com/docs/app-distribution/android/distribute-cli
