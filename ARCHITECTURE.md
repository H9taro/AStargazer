# AStargazer アーキテクチャ

この文書は、現在の `app` モジュールに実装されている構成を説明する。記載のない ViewModel、UseCase、Repository、DI コンテナ、データベース層は現状の実装には存在しない。

## アプリの概要

AStargazer は星景撮影向けの単一モジュール Android アプリである。撮影前設定、手動露出での撮影、撮影画像からのタイムラプス動画または比較明合成画像の作成を提供する。画面状態と撮影ワークフローの制御は Compose UI 層に置かれている。

## パッケージ名

- **パッケージ名 / Namespace**: `jp.hisanet.astargazer`

## 技術スタック

| 領域 | 現在の実装 |
|---|---|
| Android | `minSdk 30`、`compileSdk 37`、`targetSdk 37` |
| 言語 | Kotlin `2.2.10` |
| UI | Jetpack Compose、Material 3 |
| カメラ | CameraX `1.6.2` と Camera2 API / Camera2 interop |
| 静止画メタデータ | AndroidX ExifInterface `1.3.7` |
| 動画エンコード | Android `MediaCodec` / `MediaMuxer`（H.264/AVC、MP4） |
| 非同期処理 | Kotlin Coroutines（画面側の `CoroutineScope` / `Dispatchers` / `LaunchedEffect`） |
| ビルド | Gradle Wrapper `9.5.0`、Android Gradle Plugin `9.3.3` |
| Java 互換性 | Java source / target `11`。Gradle daemon toolchain は JDK `25` |

Compose BOM は `2026.02.01`。その他の主要依存は Activity Compose `1.8.0`、Lifecycle `2.6.1`、Core KTX `1.10.1`、JUnit `4.13.2`、AndroidX Test JUnit `1.1.5`、Espresso `3.5.1`。

以下は現在の構成には含まれない: Room、MediaStore による保存、Hilt、Timber、FFmpegKit。ログ出力には Android の `Log` API を使用する。

## モジュールと起動経路

Gradle プロジェクトは `:app` のみで構成される。ランチャー Activity は `MainActivity` で、Compose のテーマを設定して `MainScreen` を表示する。

```text
Android launcher
  └─ MainActivity
      └─ AStargazerTheme
          └─ MainScreen
              ├─ 権限確認・要求（カメラ、位置情報）
              └─ MainAppContent（画面状態と撮影ワークフローを管理）
                  ├─ 撮影前設定
                  ├─ インターバル撮影
                  └─ 仕上げ
```

主な実装ファイル:

| ファイル | 責務 |
|---|---|
| `app/src/main/java/jp/hisanet/astargazer/MainActivity.kt` | アプリ起動、Edge-to-edge 設定、Compose コンテンツ設定 |
| `app/src/main/java/jp/hisanet/astargazer/ui/MainScreen.kt` | 権限ゲート、タブ切替、Compose 状態、撮影手順・撮影処理の制御 |
| `app/src/main/java/jp/hisanet/astargazer/ui/SaveTabContent.kt` | 撮影画像プレビューと書き出し UI、画像クリア |
| `app/src/main/java/jp/hisanet/astargazer/ui/camera/CameraPreview.kt` | CameraX のプレビューをライフサイクルへバインド（無限遠ピント・AE保持） |
| `app/src/main/java/jp/hisanet/astargazer/ui/camera/CameraControlManager.kt` | 露出時間に応じた ISO 算出、Camera2 interop を介した撮影制御ヘルパー |
| `app/src/main/java/jp/hisanet/astargazer/util/Camera2BurstSession.kt` | Camera2 の JPEG セッション、連続撮影、画像・撮影結果の保存 |
| `app/src/main/java/jp/hisanet/astargazer/util/StorageHelper.kt` | 撮影・書き出しファイルの配置、一覧、空き容量、複数ISOダークフレーム確認・一括削除 |
| `app/src/main/java/jp/hisanet/astargazer/util/ImageCompositor.kt` | ダークフレーム減算と比較明合成 |
| `app/src/main/java/jp/hisanet/astargazer/util/VideoEncoderHelper.kt` | 静止画列からの MP4 タイムラプス生成 |
| `app/src/main/java/jp/hisanet/astargazer/util/ExifHelper.kt`、`LocationHelper.kt` | EXIF 撮影情報 / GPS 情報の読み書き、最終既知位置の取得 |
| `app/src/main/java/jp/hisanet/astargazer/util/FileViewerHelper.kt` | Media Scanner 登録と生成物を外部ビューアで開く処理 |

## 実行時アーキテクチャ

### UI と状態管理

`MainScreen` と `MainAppContent` が Compose の `remember` 状態および `LaunchedEffect` を用いて、選択タブ、設定手順、露出時間、撮影中フラグ、撮影数、進捗、ステータスメッセージを管理する。ボトムナビゲーションは「撮影前設定」「インターバル撮影」「仕上げ」の3画面を切り替える。専用の ViewModel や状態管理層はない。

### カメラと撮影・ワークフロー

- **撮影前設定のワークフロー**:
  1. ユーザーは露出時間を選択し、シャッターを押して3段階マルチISO試写（途中中断可能）を実行する。
  2. 試写結果表示画面（`TEST_RESULT_DISPLAY`）で、画像エリアを横スワイプして好みのISO感度画像を選択する。
  3. 選択された露出時間・ISO感度に一致するダークフレームがすでに存在する場合は自動流用する。存在しない場合のみ案内ステップ（`DARK_FRAME_NOTICE`）を経てレンズを覆って撮影し、`dark_exp_{露出秒数}s_iso_{ISO感度}.png` として保存する。
  4. 試写画像自体に対するダークフレーム減算は行わず、そのままテスト画像（`TestShooting/`）として保存され、セットアップが完了する。
- **カメラAPIの役割とライフサイクル**:
  - CameraX はプレビュー専用で、静止画取得には使用しない。プレビューは `CameraPreview` が `ProcessCameraProvider` を使って画面のライフサイクルへバインドする（無限遠ピント `0.0f`・AE自動調整）。
  - 試写、ダークフレーム撮影、インターバル撮影はすべて `Camera2BurstSession` が `CameraManager`、`CameraDevice`、`CameraCaptureSession`、`ImageReader` を直接使用する。単発撮影には `captureSingleFrame()`、連続インターバル撮影には `startRepeatingCapture()` を使う。
  - Camera2セッションを開く前にCameraXのユースケースを `unbindAll()` で解放する。Camera2セッションを閉じて撮影中状態を解除した後、画面がプレビュー状態になればCameraXのプレビューを再バインドする。

### 仕上げと書き出し

`SaveTabContent` は `StorageHelper` からインターバル撮影画像を読み、プレビューと出力モードを表示する。重い生成処理は `Dispatchers.IO` 上で実行される。「クリア」ボタンで撮影済み画像の一括削除が可能。

- HD / Full HD / 4K タイムラプス: `VideoEncoderHelper` が各画像にダークフレーム減算、クロップ、回転、文字入れを行い、Android `MediaCodec` で AVC をエンコード、`MediaMuxer` で MP4 にする。
- 最高画質の比較明合成: `ImageCompositor` が画像をダークフレーム減算し、Canvas の `PorterDuff.Mode.LIGHTEN` で合成して JPEG を出力する。
- 出力後は Media Scanner に登録し、Google Files または利用可能な一般ビューアで開く。

## ファイル保存とデータ

アプリ内データベースや Room Entity はなく、画像・動画をファイルとして保存する。`StorageHelper` は従来の共有ストレージ公開ディレクトリ API と `File` を使う。

```text
Pictures/AStargazer/
  DarkFrame/       ダークフレーム（個別名: dark_exp_{露出}s_iso_{ISO}.png）
  TestShooting/    試写画像（Test_ISO_{ISO}_{timestamp}.jpg）
  Interval/        インターバル撮影画像（IMG_{index}_{timestamp}.jpg）
  Export/          比較明合成画像
Movies/AStargazer/Export/
                   タイムラプス動画
```

撮影画像には撮影日時、ISO、露出時間、カメラ情報、利用可能な場合は GPS 情報を EXIF として記録する（試写画像・ダークフレーム画像からは位置情報を除外）。ファイルの読み書き、一覧取得、生成処理はそれぞれ UI からユーティリティを直接呼び出す。
