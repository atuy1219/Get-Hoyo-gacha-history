# Get HoYo Gacha History

Shizukuを使い、Android版の以下3タイトルからガチャ履歴を取得して端末内に保存するアプリです。

- 原神
- 崩壊：スターレイル
- ゼンレスゾーンゼロ

## 仕組み

1. ShizukuのUserServiceをADB shell権限で起動します。
2. `logcat`を監視し、ゲーム内のガチャ履歴WebViewが出力する認証付きURLを検出します。
3. URLの認証パラメータを各タイトルの公式ガチャ履歴APIに渡します。
4. 取得した履歴をアプリ内SQLiteへ保存します。

認証URL自体は保存せず、外部サーバーにも送信しません。

## 使い方

1. Shizukuをインストールして起動します。
2. 本アプリを開き、「Shizuku権限を許可」を押します。
3. 対象ゲームの「履歴を自動取得」を押します。
4. ゲームが開いたら、ガチャ画面から履歴画面を開きます。
5. URL検出後、履歴が自動で取得・保存されます。

監視は2分で終了します。履歴画面を既に開いている場合は、一度閉じてから開き直してください。

## 対応パッケージ

| ゲーム | Androidパッケージ |
|---|---|
| 原神 | `com.miHoYo.GenshinImpact` |
| 崩壊：スターレイル | `com.HoYoverse.hkrpgoversea` |
| ゼンレスゾーンゼロ | `com.HoYoverse.Nap` |

## ビルド

JDK 17とAndroid SDK 35を用意し、次を実行します。

```bash
gradle :app:assembleDebug
```

GitHub Actionsでもpushごとにdebug APKを生成します。

## 注意

- HoYoverse、COGNOSPHEREおよび各ゲームの公式アプリではありません。
- ゲームやAndroid WebViewのログ出力仕様が変更された場合、URL検出が動かなくなる可能性があります。
- 認証URLには短時間有効な認証情報が含まれるため、他人へ共有しないでください。
