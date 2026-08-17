#!/usr/bin/env bash
# Cross-compile arpscan + arpspoof for Android ARM64 (API 26+).
#
# Usage: ./build.sh [NDK_ROOT]
#
# The NDK is located from, in order:
#   1. the NDK_ROOT argument
#   2. $ANDROID_NDK_HOME
#   3. $ANDROID_SDK_ROOT/ndk/<highest version>
#   4. $ANDROID_HOME/ndk/<highest version>
#   5. ~/Android/Sdk/ndk/<highest version>      (Linux default)
#   6. ~/Library/Android/sdk/ndk/<highest version> (macOS default)
#
# Works on macOS and Linux (the NDK toolchain prebuilt is x86_64 on both).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../bin"
mkdir -p "$OUT_DIR"

# --- locate the NDK --------------------------------------------------------
NDK_ROOT="${1:-}"

if [ -z "$NDK_ROOT" ]; then
  NDK_ROOT="${ANDROID_NDK_HOME:-}"
fi

if [ -z "$NDK_ROOT" ]; then
  sdk_base=""
  for b in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/.android/sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$b" ]; then
      sdk_base="$b"
      break
    fi
  done
  if [ -n "$sdk_base" ]; then
    NDK_ROOT="$(ls -d "$sdk_base"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi

if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT" ]; then
  echo "Android NDK not found." >&2
  echo "Run: ./build.sh /path/to/android-ndk-rXX  or  export ANDROID_NDK_HOME=/path/to/ndk" >&2
  exit 1
fi

# --- pick the host toolchain prebuilt --------------------------------------
# The NDK ships a single x86_64 host toolchain per OS; macOS arm64 runs the
# darwin-x86_64 prebuilt under Rosetta.
case "$(uname -s)" in
  Darwin)             HOST_TAG="darwin-x86_64" ;;
  Linux)              HOST_TAG="linux-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *)                  HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64" ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
  echo "No prebuilt toolchain at: $TOOLCHAIN" >&2
  exit 1
fi

# --- pick the highest available API-level clang ----------------------------
CC="$(ls "$TOOLCHAIN/bin/aarch64-linux-android"*-clang 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$CC" ]; then
  echo "No aarch64-linux-android*-clang under $TOOLCHAIN/bin" >&2
  exit 1
fi
STRIP="$TOOLCHAIN/bin/llvm-strip"

echo "NDK:       $NDK_ROOT"
echo "Toolchain: $TOOLCHAIN"
echo "CC:        $CC"

"$CC" -static -O2 -Werror -Wall "$SCRIPT_DIR/arpscan.c"  -o "$OUT_DIR/arpscan"
"$CC" -static -O2 -Werror -Wall "$SCRIPT_DIR/arpspoof.c" -o "$OUT_DIR/arpspoof"
"$STRIP" "$OUT_DIR/arpscan" "$OUT_DIR/arpspoof"
file "$OUT_DIR/arpscan" "$OUT_DIR/arpspoof" || true
echo "Built: $OUT_DIR"
