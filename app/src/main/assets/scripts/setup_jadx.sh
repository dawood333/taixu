#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - JADX-CLI Reverse Engineering Suite Setup
# Hardened setup: retry downloads across mirrors, verify extracted bin/lib contents, and check the Java runtime first.
# ==============================================================================
set -e

echo "==> [TaiXu] Initializing the JADX-CLI reverse-engineering toolkit..."

mkdir -p /opt/jadx /usr/local/bin /usr/bin /tmp 2>/dev/null || true

# Use the APK-bundled JDK-jar unzip adapter when the rootfs has no native
# unzip/BusyBox. This keeps reverse-engineering setup independent of dpkg.
if ! command -v unzip >/dev/null 2>&1 && [ -x "${TAIXU_TOOL_DIR:-/opt/taixu/tools}/unzip" ]; then
    ln -sf "${TAIXU_TOOL_DIR:-/opt/taixu/tools}/unzip" /usr/local/bin/unzip 2>/dev/null || true
    export PATH="/usr/local/bin:/usr/bin:$PATH"
fi

JADX_VERSION="v1.5.0"
JADX_URLS="
https://mirror.ghproxy.com/https://github.com/skylot/jadx/releases/download/${JADX_VERSION}/jadx-${JADX_VERSION}.zip
https://gh-proxy.com/https://github.com/skylot/jadx/releases/download/${JADX_VERSION}/jadx-${JADX_VERSION}.zip
https://github.com/skylot/jadx/releases/download/${JADX_VERSION}/jadx-${JADX_VERSION}.zip
"

# Check the Java runtime first (jadx is a Java application; provide clear guidance instead of failing silently when no JVM is available).
JAVA_OK=0
if command -v java >/dev/null 2>&1 || [ -x /usr/lib/jvm/java-17-openjdk-arm64/bin/java ] || [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_OK=1
fi
if [ "$JAVA_OK" -ne 1 ]; then
    echo "==> [TaiXu] ⚠️ Java runtime not detected; JADX requires a JVM."
    echo "==> [TaiXu] 💡 Ensure the Android & Mobile Full-Stack Development Suite → Android Core Environment (OpenJDK 17) is installed."
fi

# Download and extract (verify the artifacts and retry once on failure)
download_and_extract() {
    rm -f /tmp/jadx.zip
    for url in $JADX_URLS; do
        echo "==> [TaiXu] Downloading JADX-CLI ${JADX_VERSION} ($url)..."
        if curl -fsSL -m 300 "$url" -o /tmp/jadx.zip 2>/dev/null && [ -s /tmp/jadx.zip ]; then
            echo "==> [TaiXu] Download complete ($(wc -c < /tmp/jadx.zip) bytes); extracting to /opt/jadx/..."
            rm -rf /opt/jadx/bin /opt/jadx/lib
            if (unzip -qo /tmp/jadx.zip -d /opt/jadx/ 2>/dev/null || \
                python3 -c 'import zipfile; zipfile.ZipFile("/tmp/jadx.zip").extractall("/opt/jadx/")' 2>/dev/null || \
                busybox unzip /tmp/jadx.zip -d /opt/jadx/ 2>/dev/null || true); then
                # Integrity check: both the launcher script and core JARs under lib must exist
                if [ -f /opt/jadx/bin/jadx ] && ls /opt/jadx/lib/jadx-*.jar >/dev/null 2>&1; then
                    rm -f /tmp/jadx.zip
                    return 0
                fi
                echo "==> [TaiXu] ⚠️ Extracted package is incomplete (bin/jadx or lib/jadx-*.jar is missing); trying the next mirror..."
            fi
            rm -f /tmp/jadx.zip
        fi
    done
    return 1
}

if [ ! -x /opt/jadx/bin/jadx ] || ! ls /opt/jadx/lib/jadx-*.jar >/dev/null 2>&1; then
    if ! download_and_extract; then
        echo "==> [TaiXu] ❌ JADX download failed: all mirrors are unavailable. Check the network and run setup again (or retry later with /bin/sh /opt/taixu/scripts/setup_jadx.sh in the terminal)."
        exit 1
    fi
fi

# Create global symlinks
if [ -f /opt/jadx/bin/jadx ]; then
    chmod +x /opt/jadx/bin/jadx 2>/dev/null || true
    ln -sf /opt/jadx/bin/jadx /usr/local/bin/jadx 2>/dev/null || true
    ln -sf /opt/jadx/bin/jadx /usr/bin/jadx 2>/dev/null || true
    echo "==> [TaiXu] JADX-CLI symlinks are ready ($(ls /opt/jadx/lib/jadx-*.jar 2>/dev/null | head -1))"
fi

echo "==> [TaiXu] ✅ JADX reverse-engineering toolkit setup completed!"
