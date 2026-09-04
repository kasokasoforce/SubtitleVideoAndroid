#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/cpp/whispercpp"
TAG="v1.9.2"
if [[ -f "$DEST/CMakeLists.txt" ]]; then
  echo "whisper.cpp already present: $DEST"
  exit 0
fi
rm -rf "$DEST"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp.git "$DEST"
echo "Fetched whisper.cpp $TAG"
