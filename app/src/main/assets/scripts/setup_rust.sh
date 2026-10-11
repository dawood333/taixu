#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - Rust & Android JNI Cross Compilation Suite Setup
# Supports downloading the standalone Rust ARM64 toolchain, installing the aarch64-linux-android cross-compilation target, and prebinding the NDK linker.
# ==============================================================================
set -e

echo "==> [TaiXu] Initializing the Rust and Android JNI cross-compilation environment..."

RUST_HOME="/opt/taixu/toolchains/rust"
mkdir -p "$RUST_HOME" /usr/local/bin /usr/bin /tmp /root/.cargo 2>/dev/null || true

RUST_VERSION="1.85.0"
RUST_DIST_URLS="
https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-${RUST_VERSION}-aarch64-unknown-linux-gnu.tar.gz
https://static.rust-lang.org/dist/rust-${RUST_VERSION}-aarch64-unknown-linux-gnu.tar.gz
"

RUST_STD_ANDROID_URLS="
https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-std-${RUST_VERSION}-aarch64-linux-android.tar.gz
https://static.rust-lang.org/dist/rust-std-${RUST_VERSION}-aarch64-linux-android.tar.gz
"

download_rust_core() {
    rm -f /tmp/rust-core.tar.gz
    for url in $RUST_DIST_URLS; do
        echo "==> [TaiXu] Downloading Rust ${RUST_VERSION} ARM64 toolchain ($url)..."
        if curl -fsSL -m 300 "$url" -o /tmp/rust-core.tar.gz 2>/dev/null && [ -s /tmp/rust-core.tar.gz ]; then
            echo "==> [TaiXu] Installing the standalone Rust toolchain to $RUST_HOME..."
            rm -rf /tmp/taixu-rust-core
            mkdir -p /tmp/taixu-rust-core
            tar -xzf /tmp/rust-core.tar.gz -C /tmp/taixu-rust-core --strip-components=1
            if [ -x /tmp/taixu-rust-core/install.sh ]; then
                sh /tmp/taixu-rust-core/install.sh --prefix="$RUST_HOME" --components=rustc,cargo,rust-std-aarch64-unknown-linux-gnu --disable-ldconfig >/dev/null 2>&1
            fi
            rm -rf /tmp/taixu-rust-core /tmp/rust-core.tar.gz
            if [ -x "$RUST_HOME/bin/rustc" ]; then
                return 0
            fi
        fi
    done
    return 1
}

download_rust_android_target() {
    rm -f /tmp/rust-android.tar.gz
    for url in $RUST_STD_ANDROID_URLS; do
        echo "==> [TaiXu] Downloading Rust aarch64-linux-android cross-compilation target libraries ($url)..."
        if curl -fsSL -m 180 "$url" -o /tmp/rust-android.tar.gz 2>/dev/null && [ -s /tmp/rust-android.tar.gz ]; then
            rm -rf /tmp/taixu-rust-android
            mkdir -p /tmp/taixu-rust-android
            tar -xzf /tmp/rust-android.tar.gz -C /tmp/taixu-rust-android --strip-components=1
            if [ -x /tmp/taixu-rust-android/install.sh ]; then
                sh /tmp/taixu-rust-android/install.sh --prefix="$RUST_HOME" --disable-ldconfig >/dev/null 2>&1
            fi
            rm -rf /tmp/taixu-rust-android /tmp/rust-android.tar.gz
            return 0
        fi
    done
    return 0
}

if [ ! -x "$RUST_HOME/bin/rustc" ] || [ ! -x "$RUST_HOME/bin/cargo" ]; then
    if ! download_rust_core; then
        echo "==> [TaiXu] ⚠️ Standalone Rust archive download is unavailable; trying to install through rustup..."
        export RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup
        export RUSTUP_UPDATE_ROOT=https://mirrors.tuna.tsinghua.edu.cn/rustup/rustup
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
    fi
fi

if [ -x "$RUST_HOME/bin/rustc" ]; then
    download_rust_android_target || true
fi

# Configure Cargo mirrors and the Android NDK linker
cat << 'EOF' > /root/.cargo/config.toml
[target.aarch64-linux-android]
linker = "/opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
ar = "/opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"

[source.crates-io]
replace-with = 'tuna'

[source.tuna]
registry = "sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/"
EOF

# Create symlinks in the global path
mkdir -p /opt/taixu/bin
for cmd in rustc cargo rustdoc; do
    if [ -x "$RUST_HOME/bin/$cmd" ]; then
        ln -sf "$RUST_HOME/bin/$cmd" "/opt/taixu/bin/$cmd" 2>/dev/null || true
        ln -sf "$RUST_HOME/bin/$cmd" "/usr/local/bin/$cmd" 2>/dev/null || true
        ln -sf "$RUST_HOME/bin/$cmd" "/usr/bin/$cmd" 2>/dev/null || true
    fi
done

# Write persistent environment variables
mkdir -p /etc/profile.d
cat << 'EOF' > /etc/profile.d/taixu-rust.sh
# TaiXu Rust development environment
export RUSTUP_HOME="/opt/taixu/toolchains/rust"
export CARGO_HOME="/root/.cargo"
export PATH="/opt/taixu/toolchains/rust/bin:/opt/taixu/bin:$PATH"
EOF
chmod 644 /etc/profile.d/taixu-rust.sh 2>/dev/null || true

echo "==> [TaiXu] ✅ Rust and Android JNI cross-compilation environment setup completed!"
