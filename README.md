# SubtitleVideoAndroid

PC版 `subtitle_tool_v16_white_output` の主要フローをAndroid端末内へ移した字幕動画作成アプリです。

## 目標フロー

1. Androidで動画を選択
2. whisper.cpp + `ggml-small.bin` で中国語音声を端末内文字起こし
3. 中国語SRTと翻訳指示をChatGPT Androidアプリへ共有
4. ChatGPTが出力した日本語SRTを本アプリへ読み込み（ファイルまたはクリップボード）
5. 元SRTのタイムコードを強制保持し、日本語本文だけ採用
6. Media3 Transformerで日本語字幕を動画へ焼き込み
7. `Movies/SubtitleVideo/<元名>_captioned.mp4` に保存

OpenAI API、常時起動PC、自前サーバーは使用しません。ChatGPT側の利用範囲内で動作します。

## PC版から引き継いだ字幕整形

- 1行最大: 24文字
- 1字幕イベント最大: 42文字
- 長字幕の表示目安: 最大4秒
- 長文は句読点付近を優先して自動分割
- 分割後の表示時間は文字数比で配分
- 白文字、枠なし、薄い影、下中央

PC版は FFmpeg/libass + x264 を使用していましたが、Android版は無料で完結させるため Media3 Transformer + CanvasOverlay で字幕を焼き込みます。

## 初回準備

アプリ内の「Whisperモデルを準備」から `ggml-small.bin`（約488MB）を取得します。取得後はSHA-256を検証します。

期待SHA-256:

`1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b`

## ビルド

whisper.cppを取得してからビルドします。

```bash
./scripts/fetch_whisper.sh
./gradlew assembleDebug
```

主な要件:

- JDK 17
- Android SDK 36
- NDK 28.2.13676358
- CMake 3.22.1
- arm64-v8a

GitHub Actions用 `.github/workflows/android-debug.yml` も含めています。

## 現在の段階

v0.1では主パイプラインを実装しています。実機で特に確認すべき点は、端末ごとのMediaCodec音声デコード形式、whisper.cppの速度/メモリ、Media3のH.264エンコーダ互換性、ChatGPT Android共有UIです。
