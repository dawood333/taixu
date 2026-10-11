#!/bin/sh
# ==============================================================================
# TaiXu (LinuxAIRuntime) - Android Core Development Environment Setup
# ------------------------------------------------------------------------------
# Executed once during plugin installation by the Android & Mobile Full-Stack Development Suite's android-core component.
# ==============================================================================
set -e

SDK_HOME="/opt/android-sdk"
GRADLE_VER="8.14.2"
GRADLE_SHA256="7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999"
PLATFORM_ZIP="platform-34-ext7_r03.zip"
PLATFORM_SHA1="1f2e9478d6a7601425ceaa553311dc43191f103d"
BUILD_TOOLS_VERSION="35.0.0"
BUILD_TOOLS_ZIP="build-tools_r35_linux.zip"
BUILD_TOOLS_SHA1="2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc"
# lzhiyong/android-sdk-tools provides statically linked ARM64 host tools.  The
# Google SDK build-tools archive is still used for Java tools (d8/r8), while
# these binaries replace the x86_64 host executables.
ARM64_TOOLS_VERSION="35.0.2"
ARM64_TOOLS_SHA256="DB1CEA2C4454D5F9C5A802646B2D1CF560B4EE7BADBE23E51AB8E1881BB50FC2"
ARM64_TOOLS_ARTIFACT_ID="db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"
ARM64_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/${ARM64_TOOLS_VERSION}/android-sdk-tools-static-aarch64.zip"
ARM64_TOOLS_URLS="
https://ghfast.top/${ARM64_TOOLS_URL}
https://ghproxy.net/${ARM64_TOOLS_URL}
https://gh.llkk.cc/${ARM64_TOOLS_URL}
https://gh-proxy.com/${ARM64_TOOLS_URL}
${ARM64_TOOLS_URL}
"
ARM64_TOOLS_DIR="/opt/taixu/toolchains/android/sdk-tools/artifacts/${ARM64_TOOLS_ARTIFACT_ID}"
AAPT2_STABLE_PATH="/opt/taixu/android-sdk-tools/aapt2"
TOOLCHAIN_LOCK_FILE="/opt/taixu/locks/android-toolchain.lock"

mkdir -p /opt/taixu/locks
command -v flock >/dev/null 2>&1 || {
    echo "!! [TaiXu] flock is missing; cannot safely install the Android toolchain"
    exit 1
}
exec 9>"$TOOLCHAIN_LOCK_FILE"
flock -x -w 1800 9 || {
    echo "!! [TaiXu] Android toolchain is in use by a build task; lock wait timed out"
    exit 1
}
export TAIXU_TOOLCHAIN_LOCK_HELD=1

# Remove the obsolete x86_64 AAPT2 wrapper and payload from upgraded sandboxes.
rm -rf /opt/taixu/android-sdk-tools/qemu 2>/dev/null || true

echo "==> [TaiXu] Initializing the Android Core Environment (one-time plugin setup)..."

mkdir -p /opt /usr/local/bin /usr/bin ${TAIXU_TOOL_DIR:-/opt/taixu/tools}/bin /tmp 2>/dev/null || true

# ------------------------------------------------------------------------------
# Step 1: Locate the JDK directory
# ------------------------------------------------------------------------------
JDK_DIR=""
for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-aarch64 /usr/lib/jvm/default-java; do
    if [ -x "$candidate/bin/java" ]; then
        JDK_DIR="$candidate"
        break
    fi
done
if [ -z "$JDK_DIR" ]; then
    JDK_DIR=$(ls -d /usr/lib/jvm/*/ 2>/dev/null | while read d; do
        [ -x "${d}bin/java" ] && echo "${d%/}" && break
    done)
fi
[ -z "$JDK_DIR" ] && JDK_DIR="/usr/lib/jvm/java-17-openjdk-arm64"
JAVA_HOME_RESOLVED="$JDK_DIR"
echo "==> [TaiXu] JAVA_HOME: $JAVA_HOME_RESOLVED"

if [ -x "$JAVA_HOME_RESOLVED/bin/java" ] && [ ! -x /usr/bin/java ]; then
    ln -sf "$JAVA_HOME_RESOLVED/bin/java" /usr/local/bin/java 2>/dev/null || true
    ln -sf "$JAVA_HOME_RESOLVED/bin/java" /usr/bin/java 2>/dev/null || true
fi

# The WorkshopSigningManager and offline suite share the same standard path.
# /opt/taixu/toolchains/android/jdk is the standard JAVA_HOME path. Online installations place the
# apt JDK under /usr/lib/jvm/*; create a standard-path symlink so keytool
# is reachable from the expected path for both online and offline installations. If the offline plugin already deployed the actual JDK
# directory (not a symlink), leave it unchanged.
TOOLCHAIN_JDK_LINK="/opt/taixu/toolchains/android/jdk"
if [ -d "$TOOLCHAIN_JDK_LINK" ] && [ ! -L "$TOOLCHAIN_JDK_LINK" ]; then
    echo "==> [TaiXu] Offline suite JDK detected; skipping standard-path symlink"
else
    mkdir -p /opt/taixu/toolchains/android /opt/taixu/bin 2>/dev/null || true
    ln -sfn "$JAVA_HOME_RESOLVED" "$TOOLCHAIN_JDK_LINK"
    # Place keytool / jarsigner in /opt/taixu/bin (first in PATH) so they can be invoked directly from the terminal.
    for jdk_cmd in keytool jarsigner; do
        if [ -x "$JAVA_HOME_RESOLVED/bin/$jdk_cmd" ]; then
            ln -sfn "$JAVA_HOME_RESOLVED/bin/$jdk_cmd" "/opt/taixu/bin/$jdk_cmd" 2>/dev/null || true
        fi
    done
    echo "==> [TaiXu] Standard JDK path ready: $TOOLCHAIN_JDK_LINK -> $JAVA_HOME_RESOLVED"
fi

# ------------------------------------------------------------------------------
# Step 2: Repair the JDK security configuration directories
# JDK 9+ reads its main configuration from $JAVA_HOME/conf/security/java.security; also maintain
# the lib/security and Debian-compatible paths to prevent broken links from crashing the JVM under PRoot.
# ------------------------------------------------------------------------------
SECURITY_DIR="$JAVA_HOME_RESOLVED/lib/security"
SECURITY_CONF_DIR="$JAVA_HOME_RESOLVED/conf/security"
mkdir -p "$SECURITY_DIR" "$SECURITY_CONF_DIR" /etc/java-17-openjdk/security /etc/ssl/certs/java 2>/dev/null || true
mkdir -p "$SECURITY_CONF_DIR/policy/unlimited" "$SECURITY_CONF_DIR/policy/limited" 2>/dev/null || true

echo "==> [TaiXu] Repairing the JDK java.security configuration..."

SECURITY_TMP="${SECURITY_CONF_DIR}/java.security.taixu.tmp"
cat > "$SECURITY_TMP" << 'EOF'
security.provider.1=SUN
security.provider.2=SunRsaSign
security.provider.3=SunEC
security.provider.4=SunJSSE
security.provider.5=SunJCE
security.provider.6=SunJGSS
security.provider.7=SunSASL
security.provider.8=XMLDSig
security.provider.9=SunPCSC
security.provider.10=JdkLDAP
security.provider.11=JdkSASL
security.provider.12=SunPKCS11
securerandom.source=file:/dev/urandom
securerandom.strongAlgorithms=NativePRNGBlocking:SUN,DRBG:SUN
policy.provider=sun.security.provider.PolicyFile
policy.url.1=file:${java.home}/conf/security/java.policy
policy.url.2=file:${user.home}/.java.policy
policy.expandProperties=true
policy.allowSystemProperty=true
keystore.type=pkcs12
keystore.type.compat=true
ssl.KeyManagerFactory.algorithm=SunX509
ssl.TrustManagerFactory.algorithm=PKIX
crypto.policy=unlimited
networkaddress.cache.ttl=30
networkaddress.cache.negative.ttl=0
jdk.certpath.disabledAlgorithms=MD2, MD5, SHA1 jdkCA & usage TLSServer
jdk.tls.disabledAlgorithms=SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES
jdk.tls.legacyAlgorithms=NULL, anon, RC4, DES, 3DES_EDE_CBC
jdk.tls.alpnCharset=ISO_8859_1
jdk.tls.client.protocols=TLSv1.2,TLSv1.3
jdk.tls.ephemeralDHKeySize=2048
ocsp.enable=false
EOF
mv -f "$SECURITY_TMP" "$SECURITY_CONF_DIR/java.security"
cp -f "$SECURITY_CONF_DIR/java.security" "$SECURITY_DIR/java.security" 2>/dev/null || true
cp -f "$SECURITY_CONF_DIR/java.security" /etc/java-17-openjdk/security/java.security 2>/dev/null || true

# Some minimal root filesystems include only the java binary and omit the JDK's jurisdiction policy files.
# If these files are missing while crypto.policy=unlimited is set, Gradle's SSL initialization fails.
cat > "$SECURITY_CONF_DIR/policy/unlimited/default_local.policy" << 'EOF'
grant {
    permission javax.crypto.CryptoAllPermission;
};
EOF
cat > "$SECURITY_CONF_DIR/policy/unlimited/default_US_export.policy" << 'EOF'
grant {
    permission javax.crypto.CryptoAllPermission;
};
EOF

chmod 644 "$SECURITY_DIR/java.security" 2>/dev/null || true
chmod 644 "$SECURITY_CONF_DIR/java.security" /etc/java-17-openjdk/security/java.security 2>/dev/null || true
echo "==> [TaiXu] java.security written to conf/security and compatibility paths"

# Deploy cacerts
if [ -s /opt/taixu/certs/cacerts ]; then
    cp -f /opt/taixu/certs/cacerts "$SECURITY_DIR/cacerts" 2>/dev/null || true
    cp -f /opt/taixu/certs/cacerts /etc/ssl/certs/java/cacerts 2>/dev/null || true
    echo "==> [TaiXu] cacerts deployed"
fi

# Verify that Java can load java.security
echo "==> [TaiXu] Verifying that the JVM can load java.security..."
if "$JAVA_HOME_RESOLVED/bin/java" -version >/dev/null 2>&1; then
    echo "==> [TaiXu] Verification passed: java.security can be loaded by the JVM"
else
    echo "==> [TaiXu] Warning: java.security verification did not complete"
fi

# unzip is bundled in the APK as a JDK-jar adapter. Keep a BusyBox fallback for
# older rootfs images, but never install Ubuntu's fragile unzip deb via dpkg.
if ! command -v unzip >/dev/null 2>&1; then
    if [ -x "${TAIXU_TOOL_DIR:-/opt/taixu/tools}/unzip" ]; then
        ln -sf "${TAIXU_TOOL_DIR:-/opt/taixu/tools}/unzip" /usr/local/bin/unzip 2>/dev/null || true
        export PATH="/usr/local/bin:/usr/bin:$PATH"
    fi
    if ! command -v unzip >/dev/null 2>&1 && [ -x /bin/busybox ]; then
        ln -sf /bin/busybox /usr/local/bin/unzip 2>/dev/null || true
    fi
    command -v unzip >/dev/null 2>&1 || { echo "!! [TaiXu] Unable to install the unzip utility"; exit 1; }
fi

# ------------------------------------------------------------------------------
# Step 3: Install the Android SDK platform package (android-34)
# ------------------------------------------------------------------------------
if [ ! -f "$SDK_HOME/platforms/android-34/android.jar" ]; then
    echo "==> [TaiXu] Downloading Android Platform 34 (android.jar, ~60 MB) from a configured mirror..."
    rm -rf /tmp/"$PLATFORM_ZIP" /tmp/android-platform-staging 2>/dev/null || true
    (curl -fsSL -m 300 "https://mirrors.cloud.tencent.com/AndroidSDK/$PLATFORM_ZIP" -o /tmp/"$PLATFORM_ZIP" 2>/dev/null || \
     curl -fsSL -m 300 "https://dl.google.com/android/repository/$PLATFORM_ZIP" -o /tmp/"$PLATFORM_ZIP" 2>/dev/null || true)

    if [ -f /tmp/"$PLATFORM_ZIP" ]; then
        if command -v sha1sum >/dev/null 2>&1; then
            echo "$PLATFORM_SHA1  /tmp/$PLATFORM_ZIP" | sha1sum -c - >/dev/null 2>&1 || {
                echo "!! [TaiXu] Platform package SHA-1 verification failed; discarding the corrupted file"; rm -f /tmp/"$PLATFORM_ZIP"; }
        fi
    fi

    if [ -f /tmp/"$PLATFORM_ZIP" ]; then
        echo "==> [TaiXu] Extracting the platform package to $SDK_HOME/platforms/android-34..."
        mkdir -p /tmp/android-platform-staging "$SDK_HOME/platforms"
        (unzip -qo /tmp/"$PLATFORM_ZIP" -d /tmp/android-platform-staging 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/$PLATFORM_ZIP').extractall('/tmp/android-platform-staging')" 2>/dev/null || true)
        PLATFORM_SRC=$(dirname "$(find /tmp/android-platform-staging -name android.jar -print -quit 2>/dev/null)")
        if [ -n "$PLATFORM_SRC" ] && [ -f "$PLATFORM_SRC/android.jar" ]; then
            rm -rf "$SDK_HOME/platforms/android-34"
            mkdir -p "$SDK_HOME/platforms"
            mv "$PLATFORM_SRC" "$SDK_HOME/platforms/android-34"
        else
            echo "!! [TaiXu] Platform package extraction failed; keeping the directory structure for the next setup attempt"
        fi
        rm -rf /tmp/"$PLATFORM_ZIP" /tmp/android-platform-staging 2>/dev/null || true
    else
        echo "!! [TaiXu] Platform package download failed; keeping the directory structure for the next setup attempt"
    fi
else
    echo "==> [TaiXu] Android Platform 34 package already exists; skipping"
fi

# ------------------------------------------------------------------------------
# Step 4: Pin SDK licenses and Build Tools component metadata
# ------------------------------------------------------------------------------
mkdir -p "$SDK_HOME/licenses" /root/.android/licenses 2>/dev/null || true
cat > "$SDK_HOME/licenses/android-sdk-license" << 'EOF'
8933bad161af4178b1185d1a37fbf41ea5269ab8
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
84831b9409646a918e30573bab4c9c91346d8abd
EOF
cat > "$SDK_HOME/licenses/android-sdk-preview-license" << 'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
442ba07be04be7e4a4026ec1b1952dd8223c973a
d975f751698a77b662f1254ddbeed3901e976f5a
EOF
cp -f "$SDK_HOME/licenses/"* /root/.android/licenses/ 2>/dev/null || true

# Install the official Build Tools Java components (d8/r8/lib), then replace native tools that cannot run in the ARM64
# root filesystem with ARM64-compatible binaries. This prevents AGP from downloading components during the build.
BUILD_TOOLS_DIR="$SDK_HOME/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -f "$BUILD_TOOLS_DIR/lib/d8.jar" ]; then
    echo "==> [TaiXu] Installing Android Build Tools $BUILD_TOOLS_VERSION (Java d8/r8)..."
    rm -rf /tmp/"$BUILD_TOOLS_ZIP" /tmp/android-build-tools-staging 2>/dev/null || true
    (curl -fsSL -m 300 "https://mirrors.cloud.tencent.com/AndroidSDK/$BUILD_TOOLS_ZIP" -o /tmp/"$BUILD_TOOLS_ZIP" 2>/dev/null || \
     curl -fsSL -m 300 "https://dl.google.com/android/repository/$BUILD_TOOLS_ZIP" -o /tmp/"$BUILD_TOOLS_ZIP" 2>/dev/null || true)
    if [ -f /tmp/"$BUILD_TOOLS_ZIP" ] && command -v sha1sum >/dev/null 2>&1; then
        echo "$BUILD_TOOLS_SHA1  /tmp/$BUILD_TOOLS_ZIP" | sha1sum -c - >/dev/null 2>&1 || rm -f /tmp/"$BUILD_TOOLS_ZIP"
    fi
    if [ -f /tmp/"$BUILD_TOOLS_ZIP" ]; then
        mkdir -p /tmp/android-build-tools-staging
        (unzip -qo /tmp/"$BUILD_TOOLS_ZIP" -d /tmp/android-build-tools-staging 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/$BUILD_TOOLS_ZIP').extractall('/tmp/android-build-tools-staging')" 2>/dev/null || true)
        BUILD_TOOLS_SRC=$(find /tmp/android-build-tools-staging -name source.properties -print -quit 2>/dev/null | xargs -r dirname)
        if [ -n "$BUILD_TOOLS_SRC" ] && [ -f "$BUILD_TOOLS_SRC/lib/d8.jar" ]; then
            rm -rf "$BUILD_TOOLS_DIR"
            mkdir -p "$SDK_HOME/build-tools"
            mv "$BUILD_TOOLS_SRC" "$BUILD_TOOLS_DIR"
        fi
        rm -rf /tmp/"$BUILD_TOOLS_ZIP" /tmp/android-build-tools-staging 2>/dev/null || true
    fi
fi
mkdir -p "$BUILD_TOOLS_DIR"
if [ ! -f "$BUILD_TOOLS_DIR/source.properties" ]; then
    cat > "$BUILD_TOOLS_DIR/source.properties" << EOF
Pkg.Desc=Android SDK Build-Tools 35
Pkg.Revision=$BUILD_TOOLS_VERSION
Pkg.Path=build-tools;$BUILD_TOOLS_VERSION
EOF
fi
for tool in aapt aapt2 aidl zipalign apksigner; do
    toolPath=$(command -v "$tool" 2>/dev/null || true)
    if [ -n "$toolPath" ] && [ -x "$toolPath" ]; then
        ln -sf "$toolPath" "$BUILD_TOOLS_DIR/$tool" 2>/dev/null || true
    fi
done

# ------------------------------------------------------------------------------
# ARM64 native SDK tools (aapt2 is the important one for AGP)
# ------------------------------------------------------------------------------
if [ ! -x "$ARM64_TOOLS_DIR/build-tools/aapt2" ] || \
   ! "$ARM64_TOOLS_DIR/build-tools/aapt2" version >/dev/null 2>&1; then
    if [ -e "$ARM64_TOOLS_DIR" ]; then
        echo "!! [TaiXu] Immutable ARM64 SDK Tools artifact checksum/content drift detected; refusing in-place replacement: $ARM64_TOOLS_DIR"
        exit 1
    fi
    echo "==> [TaiXu] Installing third-party ARM64 Android SDK tools (aapt2/aidl/zipalign)..."
    ARM64_ARCHIVE="/tmp/android-sdk-tools-static-aarch64-${ARM64_TOOLS_VERSION}.zip"
    ARM64_STAGING="/tmp/android-sdk-tools-aarch64-staging"
    rm -f "$ARM64_ARCHIVE"
    rm -rf "$ARM64_STAGING"
    ARM64_DOWNLOADED=0
    for ARM64_URL in $ARM64_TOOLS_URLS; do
        echo "==> [TaiXu] Attempting to download third-party ARM64 tools: $ARM64_URL"
        if curl -fsSL -m 300 "$ARM64_URL" -o "$ARM64_ARCHIVE" 2>/dev/null && [ -s "$ARM64_ARCHIVE" ]; then
            if command -v sha256sum >/dev/null 2>&1 &&
                ! echo "$ARM64_TOOLS_SHA256  $ARM64_ARCHIVE" | sha256sum -c - >/dev/null 2>&1; then
                echo "!! [TaiXu] ARM64 SDK tools SHA-256 verification failed; trying the next mirror"
                rm -f "$ARM64_ARCHIVE"
            else
                ARM64_DOWNLOADED=1
                break
            fi
        fi
    done
    [ "$ARM64_DOWNLOADED" -eq 1 ] || {
        echo "!! [TaiXu] Failed to download third-party ARM64 SDK tools; ARM64 AAPT2 will be unavailable"
        exit 1
    }
    if [ -f "$ARM64_ARCHIVE" ]; then
        mkdir -p "$ARM64_STAGING"
        if unzip -qo "$ARM64_ARCHIVE" -d "$ARM64_STAGING" 2>/dev/null; then
            # jar extraction does not preserve Unix executable bits; validate
            # presence first, then restore permissions after moving.
            if [ -f "$ARM64_STAGING/build-tools/aapt2" ]; then
                chmod 755 "$ARM64_STAGING/build-tools"/* 2>/dev/null || true
                AAPT2_MACHINE=$(od -An -tu2 -j18 -N2 "$ARM64_STAGING/build-tools/aapt2" 2>/dev/null | tr -d '[:space:]')
                [ "$AAPT2_MACHINE" = "183" ] || {
                    echo "!! [TaiXu] ARM64 AAPT2 ELF architecture check failed: e_machine=$AAPT2_MACHINE"
                    rm -rf "$ARM64_STAGING"
                    exit 1
                }
                "$ARM64_STAGING/build-tools/aapt2" version >/dev/null 2>&1 || {
                    echo "!! [TaiXu] ARM64 AAPT2 cannot start in the current RootFS"
                    rm -rf "$ARM64_STAGING"
                    exit 1
                }
                mkdir -p "$(dirname "$ARM64_TOOLS_DIR")"
                mv "$ARM64_STAGING" "$ARM64_TOOLS_DIR"
            else
                echo "!! [TaiXu] ARM64 SDK tools package lacks build-tools/aapt2; ignoring this package"
                rm -rf "$ARM64_STAGING"
            fi
        else
            echo "!! [TaiXu] Failed to extract ARM64 SDK tools"
            rm -rf "$ARM64_STAGING"
            exit 1
        fi
        rm -f "$ARM64_ARCHIVE"
    fi
fi

# Put the ARM64 tools first in PATH.  Keep the SDK directory's Java tools and
# metadata intact; only native host executables are replaced.
if [ -x "$ARM64_TOOLS_DIR/build-tools/aapt2" ] && \
   "$ARM64_TOOLS_DIR/build-tools/aapt2" version >/dev/null 2>&1; then
    for tool in aapt aapt2 aidl zipalign; do
        if [ -x "$ARM64_TOOLS_DIR/build-tools/$tool" ]; then
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "$BUILD_TOOLS_DIR/$tool"
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "/usr/local/bin/$tool" 2>/dev/null || true
            ln -sf "$ARM64_TOOLS_DIR/build-tools/$tool" "/usr/bin/$tool" 2>/dev/null || true
        fi
    done
    mkdir -p "$(dirname "$AAPT2_STABLE_PATH")"
    ln -sf "$ARM64_TOOLS_DIR/build-tools/aapt2" "$AAPT2_STABLE_PATH"
    # Formal builds use the immutable digest path. The stable link remains only
    # for old projects and interactive terminal compatibility.
    LEGACY_ARM64_TOOLS_DIR="/opt/taixu/android-sdk-tools/${ARM64_TOOLS_VERSION}"
    if [ -e "$LEGACY_ARM64_TOOLS_DIR" ] && [ ! -L "$LEGACY_ARM64_TOOLS_DIR" ]; then
        mv "$LEGACY_ARM64_TOOLS_DIR" "${LEGACY_ARM64_TOOLS_DIR}.legacy.$(date +%s)"
    fi
    ln -sfn "$ARM64_TOOLS_DIR" "$LEGACY_ARM64_TOOLS_DIR"
    export TAIXU_AAPT2_PATH="$ARM64_TOOLS_DIR/build-tools/aapt2"
else
    export TAIXU_AAPT2_PATH=""
fi

# Install the pinned Linux AArch64 NDK while the same exclusive toolchain lock
# is held. This prevents AGP from observing a half-installed SDK/NDK view.
/bin/sh /opt/taixu/scripts/setup_termux_ndk.sh
. /opt/taixu/toolchains/android/ndk/taixu-ndk.env

# Remove only TaiXu's legacy project-level AAPT2 overrides. Formal injection is
# now user-scoped and points at the immutable digest path, so projects cannot
# pin themselves back to a replaceable compatibility symlink.
if [ -d /workspace ]; then
    find /workspace -type f -name gradle.properties -exec sed -i \
        '\#^[[:space:]]*android\.aapt2FromMavenOverride[[:space:]]*=[[:space:]]*/opt/taixu/android-sdk-tools/#d' {} \; \
        2>/dev/null || true
    find /workspace -type f \( -name build.gradle -o -name build.gradle.kts \) -exec sed -i \
        -e 's/buildToolsVersion = "34\.0\.0"/buildToolsVersion = "35.0.0"/g' \
        -e 's/buildToolsVersion "34\.0\.0"/buildToolsVersion "35.0.0"/g' {} \; \
        2>/dev/null || true
fi

# ------------------------------------------------------------------------------
# Step 5: Link ADB / AAPT / zipalign / apksigner
# ------------------------------------------------------------------------------
for tool in aapt aapt2 adb zipalign apksigner; do
    DEB_BIN=$(command -v "$tool" 2>/dev/null || echo "/usr/bin/$tool")
    if [ -x "$DEB_BIN" ] && [ ! -x "/usr/local/bin/$tool" ]; then
        ln -sf "$DEB_BIN" "/usr/local/bin/$tool" 2>/dev/null || true
    fi
done

# ------------------------------------------------------------------------------
# Step 6: Install standalone official Gradle 8.14.2
# ------------------------------------------------------------------------------
if [ ! -f /opt/gradle-$GRADLE_VER/lib/gradle-launcher-$GRADLE_VER.jar ]; then
    echo "==> [TaiXu] Downloading Gradle $GRADLE_VER (~120 MB) from a configured mirror..."
    rm -rf /tmp/gradle-$GRADLE_VER.zip /opt/gradle-$GRADLE_VER 2>/dev/null || true
    (curl -fsSL -m 600 https://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VER-bin.zip -o /tmp/gradle-$GRADLE_VER.zip 2>/dev/null || \
     curl -fsSL -m 600 https://mirrors.huaweicloud.com/gradle/gradle-$GRADLE_VER-bin.zip -o /tmp/gradle-$GRADLE_VER.zip 2>/dev/null || true)

    if [ -f /tmp/gradle-$GRADLE_VER.zip ]; then
        if command -v sha256sum >/dev/null 2>&1; then
            echo "$GRADLE_SHA256  /tmp/gradle-$GRADLE_VER.zip" | sha256sum -c - >/dev/null 2>&1 || {
                echo "!! [TaiXu] Gradle package SHA-256 verification failed; discarding the corrupted file"; rm -f /tmp/gradle-$GRADLE_VER.zip; }
        fi
    fi

    if [ -f /tmp/gradle-$GRADLE_VER.zip ]; then
        echo "==> [TaiXu] Extracting Gradle $GRADLE_VER to /opt/..."
        (unzip -qo /tmp/gradle-$GRADLE_VER.zip -d /opt/ 2>/dev/null || \
         python3 -c "import zipfile; zipfile.ZipFile('/tmp/gradle-$GRADLE_VER.zip').extractall('/opt/')" 2>/dev/null || \
         busybox unzip /tmp/gradle-$GRADLE_VER.zip -d /opt/ 2>/dev/null || true)
        rm -f /tmp/gradle-$GRADLE_VER.zip
    fi
fi

if [ -d /opt/gradle-$GRADLE_VER/bin ]; then
    chmod +x /opt/gradle-$GRADLE_VER/bin/gradle 2>/dev/null || true
    ln -sf /opt/gradle-$GRADLE_VER/bin/gradle /usr/local/bin/gradle 2>/dev/null || true
    ln -sf /opt/gradle-$GRADLE_VER/bin/gradle /usr/bin/gradle 2>/dev/null || true
    echo "==> [TaiXu] Gradle $GRADLE_VER is ready"
fi

# ------------------------------------------------------------------------------
# Step 7: Configure global Gradle mirrors
# ------------------------------------------------------------------------------
mkdir -p /root/.gradle
cat << 'EOF' > /root/.gradle/init.gradle
// TaiXu: 全局强制Aliyun mirror，避免 Gradle 从国外源拉取依赖（国内网络慢/被墙）。
gradle.beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    settings.dependencyResolutionManagement.repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://storage.flutter-io.cn/download.flutter.io' }
        google()
        mavenCentral()
    }
}
EOF

# AGP normally resolves aapt2 from Maven and the Linux artifact is x86_64.
# Persist the native ARM64 override at Gradle user scope so plain `gradle` and
# project wrapper invocations are protected too, not only TaiXu's build scripts.
# Replace only the managed key and preserve every other user property.
GRADLE_PROPERTIES=/root/.gradle/gradle.properties
GRADLE_PROPERTIES_TMP="${GRADLE_PROPERTIES}.taixu.tmp"
if [ -n "$TAIXU_AAPT2_PATH" ] && [ -x "$TAIXU_AAPT2_PATH" ] && \
   "$TAIXU_AAPT2_PATH" version >/dev/null 2>&1; then
    if [ -f "$GRADLE_PROPERTIES" ]; then
        sed \
            -e '/^[[:space:]]*android\.aapt2FromMavenOverride[[:space:]]*=/d' \
            -e '/^[[:space:]]*android\.builder\.sdkDownload[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.daemon[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.parallel[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.workers\.max[[:space:]]*=/d' \
            -e '/^[[:space:]]*org\.gradle\.jvmargs[[:space:]]*=/d' \
            -e '/^[[:space:]]*kotlin\.daemon\.jvmargs[[:space:]]*=/d' \
            "$GRADLE_PROPERTIES" > "$GRADLE_PROPERTIES_TMP"
    else
        : > "$GRADLE_PROPERTIES_TMP"
    fi
    printf '\n# TaiXu: immutable ARM64 toolchain and mobile-safe build limits.\nandroid.aapt2FromMavenOverride=%s\nandroid.builder.sdkDownload=false\norg.gradle.daemon=false\norg.gradle.parallel=false\norg.gradle.workers.max=2\norg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8\nsystemProp.org.gradle.internal.http.connectionTimeout=30000\nsystemProp.org.gradle.internal.http.socketTimeout=60000\nkotlin.daemon.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m\n' \
        "$TAIXU_AAPT2_PATH" >> "$GRADLE_PROPERTIES_TMP"
    mv -f "$GRADLE_PROPERTIES_TMP" "$GRADLE_PROPERTIES"
    echo "==> [TaiXu] Global Gradle AAPT2 override: $TAIXU_AAPT2_PATH"
else
    echo "!! [TaiXu] AAPT2 could not start; global Gradle override was not written"
    exit 1
fi

# ------------------------------------------------------------------------------
# Step 8: Persist core environment variables (global)
# ------------------------------------------------------------------------------
cat << EOF > /etc/profile.d/taixu-android.sh
# TaiXu Android development environment (managed by android-core plugin)
export JAVA_HOME="$JAVA_HOME_RESOLVED"
export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$SDK_HOME"
export GRADLE_HOME="/opt/gradle-$GRADLE_VER"
export TAIXU_AAPT2_PATH="${TAIXU_AAPT2_PATH:-}"
export TAIXU_NDK_PATH="$TAIXU_NDK_PATH"
export TAIXU_NDK_VERSION="$TAIXU_NDK_VERSION"
export TAIXU_NDK_SHA256="$TAIXU_NDK_SHA256"
export ANDROID_NDK_HOME="$TAIXU_NDK_PATH"
export ANDROID_NDK_ROOT="$TAIXU_NDK_PATH"
export TAIXU_LLVM_STRIP_PATH="$TAIXU_LLVM_STRIP_PATH"
# /opt/taixu/bin must remain first in PATH so terminal and Agent invocations of gradle/gradlew
# run the TaiXu ARM64 toolchain checks before invoking the pinned Gradle.
export PATH="/opt/taixu/bin:\$JAVA_HOME/bin:\$GRADLE_HOME/bin:\$PATH"
# PRoot sandbox: use non-blocking entropy
export _JAVA_OPTIONS="-Djava.security.egd=file:/dev/urandom"
EOF

cat << EOF > /etc/environment
JAVA_HOME=$JAVA_HOME_RESOLVED
ANDROID_HOME=$SDK_HOME
ANDROID_SDK_ROOT=$SDK_HOME
GRADLE_HOME=/opt/gradle-$GRADLE_VER
TAIXU_AAPT2_PATH=$TAIXU_AAPT2_PATH
TAIXU_NDK_PATH=$TAIXU_NDK_PATH
TAIXU_NDK_VERSION=$TAIXU_NDK_VERSION
TAIXU_NDK_SHA256=$TAIXU_NDK_SHA256
ANDROID_NDK_HOME=$TAIXU_NDK_PATH
ANDROID_NDK_ROOT=$TAIXU_NDK_PATH
TAIXU_LLVM_STRIP_PATH=$TAIXU_LLVM_STRIP_PATH
PATH=/opt/taixu/bin:$JAVA_HOME_RESOLVED/bin:/opt/gradle-$GRADLE_VER/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
_JAVA_OPTIONS=-Djava.security.egd=file:/dev/urandom
EOF

if [ -f /root/.bashrc ] && ! grep -q "taixu-android" /root/.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/taixu-android.sh 2>/dev/null || true' >> /root/.bashrc
fi
if [ -f /etc/bash.bashrc ] && ! grep -q "taixu-android" /etc/bash.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/taixu-android.sh 2>/dev/null || true' >> /etc/bash.bashrc
fi

# ------------------------------------------------------------------------------
# Step 9: Final verification
# ------------------------------------------------------------------------------
echo "==> [TaiXu] Setup self-check:"

[ -x "$JAVA_HOME_RESOLVED/bin/java" ] && echo "    [OK] Java runtime" || { echo "    [MISS] Java runtime"; exit 1; }
# 工坊签名（创建/导入 keystore）依赖规范路径下的 keytool，缺失时签名功能不可用。
[ -x /opt/taixu/toolchains/android/jdk/bin/keytool ] && echo "    [OK] keytool signing toolchain (standard path)" || { echo "    [MISS] keytool signing toolchain"; exit 1; }
[ -s "$SECURITY_CONF_DIR/java.security" ] && [ -f "$SECURITY_CONF_DIR/policy/unlimited/default_local.policy" ] && echo "    [OK] java.security + crypto policy" || { echo "    [MISS] java.security / crypto policy"; exit 1; }
[ -s "$SECURITY_DIR/cacerts" ] && "$JAVA_HOME_RESOLVED/bin/keytool" -list -keystore "$SECURITY_DIR/cacerts" -storetype PKCS12 -storepass changeit >/dev/null 2>&1 && echo "    [OK] Java cacerts (PKCS12)" || { echo "    [MISS] Java cacerts"; exit 1; }
[ -f "$SDK_HOME/platforms/android-34/android.jar" ] && echo "    [OK] Android Platform 34" || { echo "    [MISS] Android platform package"; exit 1; }
[ -s "$SDK_HOME/licenses/android-sdk-license" ] && echo "    [OK] Android SDK licenses" || { echo "    [MISS] Android SDK licenses"; exit 1; }
[ -f "$BUILD_TOOLS_DIR/source.properties" ] && [ -f "$BUILD_TOOLS_DIR/lib/d8.jar" ] && echo "    [OK] Build-Tools $BUILD_TOOLS_VERSION (Java d8/r8 + ARM64 native tools)" || { echo "    [MISS] Build-Tools $BUILD_TOOLS_VERSION"; exit 1; }
[ -f "$TAIXU_NDK_PATH/source.properties" ] && [ -x "$TAIXU_NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] && echo "    [OK] lzhiyong ARM64 NDK $TAIXU_NDK_VERSION" || { echo "    [MISS] Pinned ARM64 NDK"; exit 1; }
[ -x "$TAIXU_LLVM_STRIP_PATH" ] && "$TAIXU_LLVM_STRIP_PATH" --version >/dev/null 2>&1 && echo "    [OK] NDK ARM64 llvm-strip" || { echo "    [MISS] NDK ARM64 llvm-strip"; exit 1; }
AAPT2_MACHINE=$(od -An -tu2 -j18 -N2 "$TAIXU_AAPT2_PATH" 2>/dev/null | tr -d '[:space:]')
[ "$AAPT2_MACHINE" = "183" ] && [ -x "$TAIXU_AAPT2_PATH" ] && "$TAIXU_AAPT2_PATH" version >/dev/null 2>&1 && echo "    [OK] ARM64 AAPT2: $TAIXU_AAPT2_PATH" || { echo "    [MISS] ARM64 AAPT2 is missing, has the wrong architecture, or cannot start"; exit 1; }
[ -f /opt/gradle-$GRADLE_VER/lib/gradle-launcher-$GRADLE_VER.jar ] && echo "    [OK] Gradle $GRADLE_VER" || { echo "    [MISS] Gradle"; exit 1; }
[ -f /root/.gradle/init.gradle ] && echo "    [OK] Aliyun mirror" || { echo "    [MISS] Gradle mirror configuration"; exit 1; }
grep -Fqx "android.aapt2FromMavenOverride=$TAIXU_AAPT2_PATH" /root/.gradle/gradle.properties && echo "    [OK] Global Gradle AAPT2 override" || { echo "    [MISS] Global Gradle AAPT2 override"; exit 1; }
grep -Fqx "android.builder.sdkDownload=false" /root/.gradle/gradle.properties && echo "    [OK] Gradle SDK auto-download disabled" || { echo "    [MISS] Gradle SDK auto-download policy"; exit 1; }
grep -Fqx "org.gradle.daemon=false" /root/.gradle/gradle.properties && \
grep -Fqx "org.gradle.parallel=false" /root/.gradle/gradle.properties && \
grep -Fqx "org.gradle.workers.max=2" /root/.gradle/gradle.properties && \
grep -Fq "org.gradle.jvmargs=-Xmx1024m" /root/.gradle/gradle.properties && \
    echo "    [OK] Gradle mobile resource and daemon policy" || { echo "    [MISS] Gradle mobile resource policy"; exit 1; }
[ -f /root/.gradle/init.d/taixu-android-ndk.gradle ] && grep -Fq "$TAIXU_NDK_PATH" /root/.gradle/init.d/taixu-android-ndk.gradle && echo "    [OK] Gradle pinned NDK path injection" || { echo "    [MISS] Gradle NDK path injection"; exit 1; }
grep -Fq "export JAVA_HOME=\"$JAVA_HOME_RESOLVED\"" /etc/profile.d/taixu-android.sh && echo "    [OK] Persistent JAVA_HOME environment" || { echo "    [MISS] Persistent JAVA_HOME environment"; exit 1; }

SSL_PROBE="/tmp/TaiXuSslProbe.java"
cat > "$SSL_PROBE" << 'EOF'
import javax.net.ssl.SSLContext;
public class TaiXuSslProbe {
    public static void main(String[] args) throws Exception {
        SSLContext.getDefault().createSSLEngine();
    }
}
EOF
if "$JAVA_HOME_RESOLVED/bin/java" \
    -Djavax.net.ssl.trustStore="$SECURITY_DIR/cacerts" \
    -Djavax.net.ssl.trustStoreType=PKCS12 \
    -Djavax.net.ssl.trustStorePassword=changeit \
    "$SSL_PROBE" >/dev/null 2>&1; then
    echo "    [OK] Java SSLContext"
else
    echo "    [MISS] Java SSLContext"
    rm -f "$SSL_PROBE"
    exit 1
fi
rm -f "$SSL_PROBE"

echo "==> [TaiXu] ✅ Android Core Environment plugin setup completed!"
