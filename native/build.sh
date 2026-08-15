#!/usr/bin/env bash
# Cross-compile arpscan + arpspoof for Android ARM64 (API 26+).
# Usage: ./build.sh [NDK_ROOT]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../bin"
mkdir -p "$OUT_DIR"

NDK_ROOT="${1:-$HOME/Library/Android/sdk/ndk/$(ls "$HOME/Library/Android/sdk/ndk" 2>/dev/null | sort -V | tail -1)}"
if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then
  echo "NDK not found. Pass path: ./build.sh /path/to/android-ndk-rxx" >&2
  exit 1
fi

OS_HOST="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$(uname -m)" in
  arm64) ARCH="x86_64" ;;
  *)     ARCH="$(uname -m)" ;;
esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$OS_HOST-$ARCH"
CC="$TOOLCHAIN/bin/aarch64-linux-android33-clang"

echo "Using $CC"
"$CC" -static -O2 -Werror -Wall "$SCRIPT_DIR/arpscan.c"  -o "$OUT_DIR/arpscan"
"$CC" -static -O2 -Werror -Wall "$SCRIPT_DIR/arpspoof.c" -o "$OUT_DIR/arpspoof"
"$TOOLCHAIN/bin/llvm-strip" "$OUT_DIR/arpscan" "$OUT_DIR/arpspoof"
file "$OUT_DIR/arpscan" "$OUT_DIR/arpspoof"
echo "Built: $OUT_DIR"