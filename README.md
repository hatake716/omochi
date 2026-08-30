<div align="center">

# Omochi

**Androidだけで動く、タッチファーストのCode - OSSワークベンチ**

Google Play / Google Play Services・root権限・外部Termuxを前提にしない、
ARM64 Android向けのオープンソースIDEです。

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-555555)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)
![Status](https://img.shields.io/badge/status-v0.1.0%20development-C96954)

</div>

> [!IMPORTANT]
> 現在は最初の実装版です。Android/Kotlinコンパイル、単体テスト、lint、APK構成を検証していますが、
> Pixel 10aへdebug APKを導入した時点で端末がユーザー操作中だったため、アプリを勝手に前面へ出していません。
> 初回ダウンロードから実際の編集までの実機E2Eは未完了です。詳細は
> [機能マトリクス](docs/FEATURE_MATRIX.md) を参照してください。

## Omochiとは

Omochiは、Code - OSS系のワークベンチをAndroidアプリ内のローカルLinux環境で動かし、
WebViewを通じてタッチ操作へ最適化して表示します。ブラウザ上の外部サービスへコードを送る方式ではありません。
IDEサーバーは端末内の `127.0.0.1` に動的選択した空きポートでだけ待ち受け、256-bitの
アプリ専用ランダムパスワードで保護します。プロジェクトはアプリ専用領域の `/workspace` に保存します。

中核には公式の `code-server 4.133.0`（Code 1.133.0）ARM64 standaloneを使います。
APKへ巨大なIDE本体を直接同梱せず、ユーザーが初回セットアップを押した時だけ公式GitHub Releaseから取得し、
固定したSHA-256を検証してから展開します。

## 実装済みの体験

- Explorer、複数タブ、エディタグループ／分割、差分エディタ、ミニマップ、折り畳み
- ファイル内検索・置換、ワークスペース全体検索、正規表現・除外設定
- 統合ターミナル、複数ターミナル、タスク、Problems、Output、Debug Console
- Git clone、差分、ステージ、コミット、ブランチ、pull / pushなどの組み込みSource Control
- 設定、キーバインド、コマンドパレット、Quick Open、ワークスペース設定
- JavaScript / TypeScript / JSON / HTML / CSS / Markdown等、配布物に含まれる組み込み言語機能
- SAFによるファイル／フォルダ取込と、上書きしないタイムスタンプ付きワークスペース書出
- macOS風のウィンドウクローム、交通信号ボタン、淡色パレット、角丸、半透明感
- Explorer、検索、Git、ターミナル、コマンドパレットを直接開く44dp級タッチボタン
- Esc / Ctrl / Alt / Shift / Tab / 矢印 / Save / Undo / Redo / Find等のタッチキーバー
- 日本語を含むAndroid IME入力、ハードウェアキーボード、ピンチ・スクロール

外部拡張マーケットは製品設定から除去し、Extensions ActivityもAndroid側のタッチレイヤーで非表示にします。
Gitや基本言語サポート等、Code - OSSの中核として配布される組み込みコンポーネントは残します。

## Google Playを前提にしない設計

- 配布はAPKの直接インストールを想定
- Google Play Services、Firebase、Google Sign-In、Play Integrityへの依存なし
- root権限なし
- 外部のTermuxアプリ／`com.termux` データにアクセスしない
- Linux rootfs、IDE、設定、GitリポジトリはすべてOmochiのアプリ専用領域
- 共有ストレージはAndroid標準のSAFでユーザーが選択した項目だけを扱う
- ネットワーク権限は初回のUbuntu／IDE取得、Git、パッケージ管理、開発中アプリの通信に使用

Google Mavenから取得するAndroidXはビルド時のUIライブラリであり、端末上のGoogle Playや
Googleアカウントを要求するものではありません。

## アーキテクチャ

```text
┌──────────────── Android application ────────────────┐
│ macOS-like Compose shell                            │
│   Welcome / setup / SAF import-export / touch bar   │
│                         │                            │
│ Android WebView ─────── HTTP + WebSocket ─────┐     │
│                                               │     │
│ PRoot (Android/Bionic, no root)                │     │
│   Ubuntu Base 24.04.4                          │     │
│   code-server 4.133.0 ── 127.0.0.1:<dynamic> ◀─┘     │
│   Git / SSH / ripgrep / bash                         │
│                         │                            │
│                 /workspace bind                      │
└─────────────────────────┼────────────────────────────┘
                          │
                  app-private storage
```

詳しいプロセス境界、保存先、脅威モデル、更新手順は
[ARCHITECTURE.md](docs/ARCHITECTURE.md) にあります。

## 必要環境

- ARM64（`arm64-v8a`）Android端末
- Android 8.0 / API 26以降
- Android System WebViewの比較的新しいバージョン
- 初回セットアップ用のインターネット接続
- 最低でも約1 GiB、実用上は2 GiB以上の空き容量を推奨

初回取得量の目安は、Ubuntu Base約29 MiB、code-server約219 MiBに加え、Git等のUbuntuパッケージです。
アーカイブ展開後はさらに容量を使います。

## 使い方

1. APKを端末へ直接インストールします。
2. Omochiを開き、**IDEをセットアップ**を押します。
3. Ubuntu Base、code-server、Git／SSH／検索ツールの準備が100%になるまで待ちます。
4. **ワークベンチを開く**を押します。
5. Explorerで新規作成するか、ホーム画面の**ファイル取込／フォルダ取込**を使います。
6. Gitリポジトリは統合ターミナルで `git clone` するか、Source Controlから開きます。

セットアップは検証済みのダウンロードキャッシュを再利用します。途中で失敗した場合は再度ボタンを押せます。

## ビルド

### 必要なもの

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- ARM64 APKを扱えるLinux / macOS / Windows環境

NixOSでは、SDKに含まれる `aapt2` を使うよう次の値を設定してください。

```bash
export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2"
```

### 検証とAPK生成

```bash
./gradlew --no-daemon \
  :app:verifyEmbeddedRuntime \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

生成物:

```text
app/build/outputs/apk/debug/app-debug.apk
```

インストール:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

DebugビルドのアプリIDは `io.github.hatake716.omochi.debug`、Releaseビルドは
`io.github.hatake716.omochi` です。

## 固定している配布物

| Component | Version | Verification |
|---|---:|---|
| Ubuntu Base ARM64 | 24.04.4 | SHA-256 `04207713…37ac7ff2` |
| code-server ARM64 standalone | 4.133.0 | SHA-256 `d999d8b0…d1c71147` |
| Code | 1.133.0 | code-server公式配布物に内包 |
| PRoot | Termux build based on 5.1.107.92 | バイナリ＋対応ソースをAPKに同梱 |

code-server更新時は、バージョンだけでなくRelease assetの公式SHA-256、展開後ディレクトリ、
CLIフラグ、Codeのバージョンをまとめて検証します。

## 外部拡張を除外する境界

このプロジェクトで「拡張機能を除く」は次を意味します。

- Open VSX等の外部Extension Galleryを製品設定から除去
- Extensionsサイドバーを非表示
- 拡張の自動確認／自動更新を無効化
- 外部拡張の正常動作を製品要件・テスト対象に含めない
- Git、基本言語、テーマ等、Code - OSS配布物に組み込まれた機能は中核機能として使用

言語コンパイラ、SDK、デバッガ実行ファイルはデスクトップ版VS Codeと同様、対象プロジェクトに応じて
Ubuntu環境へ導入する必要があります。外部拡張なしで利用できない言語固有機能は、Omochiが独自に
互換性を保証する範囲外です。

## セキュリティとプライバシー

- IDEサーバーは起動ごとに選んだ空きポートで `127.0.0.1` のみにbindし、LANへ公開しません。
- loopback以外のURLはWebView内で開かず、Androidの外部ブラウザへ渡します。
- WebViewの通常ファイルアクセスとcontentアクセスは無効です。
- code-serverはアプリ専用領域に生成した256-bitランダムパスワードで認証し、WebViewだけを自動ログインさせます。
- Workspace TrustはCode - OSS標準のまま有効です。テレメトリとcode-server更新確認は無効化しています。
- code-serverアーカイブは展開前にSHA-256を検証します。
- tar展開先はcanonical pathで検証し、`../` によるアプリ領域外への脱出を拒否します。
- SAF取込は既存項目を上書きせず、衝突時は新しい名前を作ります。
- SAF書出は常に新しいタイムスタンプ付きフォルダを作ります。
- SAF書出はシンボリックリンクを追跡せず、ワークスペース外の内容を誤って持ち出さないよう拒否します。

PRootはAndroidアプリのUID権限を越えません。「root」と表示されるLinuxユーザーはPRoot内の見かけ上のrootであり、
Android端末のroot権限ではありません。

AndroidのNetwork Security ConfigはWebView／Androidフレームワーク側の平文通信だけをloopbackへ制限します。
統合ターミナル、Git、apt、実行した開発ツールは、アプリのINTERNET権限の範囲で外部ネットワークへ接続できます。

## 現在の制約

- ARM64専用。x86_64エミュレータ、32bit ARMは未対応です。
- 初回セットアップは約250 MiB以上をネットワーク取得します。
- Android WebView上のCode - OSSなので、Electron固有の複数ネイティブウィンドウ等はありません。
- サーバープロセスは現在アプリプロセス管理です。ワークベンチを閉じた後の常駐実行は保証しません。
- Androidの省メモリ処理、画面回転、長時間バックグラウンド、巨大リポジトリは実機検証が必要です。
- USB／Bluetooth／シリアル等のAndroidハードウェア統合はまだありません。
- 外部拡張、Microsoft Settings Sync、Remote Development系拡張は対象外です。

## ドキュメント

- [アーキテクチャとセキュリティ境界](docs/ARCHITECTURE.md)
- [機能マトリクス](docs/FEATURE_MATRIX.md)
- [実機テスト手順](docs/TESTING.md)

## ライセンス

Omochiのアプリケーションコードは [Apache License 2.0](LICENSE) です。
APKに含まれる第三者コンポーネントにはそれぞれのライセンスが適用されます。
GPL/LGPL対象バイナリの対応ソース、ビルドレシピ、ライセンス本文は
`app/src/main/assets/legal/` に同梱しています。

OmochiはVisual Studio Code、Microsoft Corporation、Coder Technologies, Inc.と提携していません。
各名称・商標は各権利者に帰属します。
