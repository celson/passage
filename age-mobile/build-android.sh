#!/usr/bin/env bash
set -euo pipefail

# Build script for compiling age-mobile into Android .so libraries
# Prerequisites:
# 1. Android NDK installed and ANDROID_NDK_HOME set
# 2. cargo ndk installed: cargo install cargo-ndk
# 3. Rust targets added:
#    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

OUTPUT_JNI_DIR="${1:-../android/core/crypto/src/main/jniLibs}"
KOTLIN_OUT_DIR="${2:-../android/core/crypto/src/main/java}"

echo "=== Building age-mobile for Android ==="
echo "Target JNI Directory: $OUTPUT_JNI_DIR"
echo "Target Kotlin Directory: $KOTLIN_OUT_DIR"

# Space-separated ABI list; override (e.g. ABIS="arm64-v8a") to speed up non-release builds
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64 x86}"
TARGET_ARGS=()
for abi in $ABIS; do
    mkdir -p "$OUTPUT_JNI_DIR/$abi"
    TARGET_ARGS+=(-t "$abi")
done

cargo ndk "${TARGET_ARGS[@]}" --platform 26 -o "$OUTPUT_JNI_DIR" build --release --locked

echo "=== Generating Kotlin UniFFI bindings ==="
# Build host dylib to extract interface metadata
cargo build --locked --release
DYLIB_PATH="target/release/libage_mobile.dylib"
if [[ "$OSTYPE" == "linux"* ]]; then
    DYLIB_PATH="target/release/libage_mobile.so"
fi

cargo run --locked --bin uniffi-bindgen -- generate \
    --library "$DYLIB_PATH" \
    --language kotlin \
    --out-dir "$KOTLIN_OUT_DIR" \
    --config uniffi.toml

echo "=== Build completed successfully ==="
