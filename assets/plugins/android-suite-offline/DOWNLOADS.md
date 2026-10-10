# ARM64 Resource Download Manifest

This plugin is **Android-only**. The Flutter bundle retains only the resources needed to build Android apps; it does not support Web, iOS, Windows, macOS, or Linux desktop builds. iOS cannot be built inside Linux ARM64 PRoot either, because it requires macOS/Xcode.

Download only the AArch64/ARM64 resources listed below and save each file under its target name in `payload/archives/`. Do not download variants whose filenames contain `x86`, `x86_64`, or `amd64`.

| Target filename | Download URL | Notes |
|---|---|---|
| `jdk-17-aarch64-linux.tar.gz` | https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz | Eclipse Temurin JDK 17 AArch64; SHA-256 `457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41` |
| `gradle-8.14.2-bin.zip` | https://mirrors.cloud.tencent.com/gradle/gradle-8.14.2-bin.zip | Gradle is a Java archive, not a host ELF binary; SHA-256 `7197a12f450794931532469d4ff21a59ea2c1cd59a3ec3f89c035c3c420a6999` |
| `platform-34-ext7_r03.zip` | https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip | Android Platform 34, primarily architecture-independent resources such as `android.jar`; SHA-1 `1f2e9478d6a7601425ceaa553311dc43191f103d` |
| `build-tools_r35_linux.zip` | https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip | Only JARs/resources are used; the installer removes non-ARM64 ELF binaries; SHA-1 `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc` |
| `android-sdk-tools-static-aarch64.zip` | https://ghfast.top/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip | ARM64 `aapt2/aidl/zipalign`; SHA-256 `DB1CEA2C4454D5F9C5A802646B2D1CF560B4EE7BADBE23E51AB8E1881BB50FC2` |
| `android-ndk-r29-aarch64.tar.gz` | Stream-converted from upstream: https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz | Linux AArch64 NDK; only the outer XZ compression is converted to GZip, the tar contents remain unchanged; SHA-256 `92ffb0343d2325f0bdb77c68c9ffb049e86b4266a0881db594dddbc2d6f9519f` |
| `cmake-linux-aarch64.tar.gz` | https://github.com/Kitware/CMake/releases/download/v3.31.7/cmake-3.31.7-linux-aarch64.tar.gz | CMake for Linux AArch64 |
| `ninja-linux-aarch64.zip` | https://github.com/ninja-build/ninja/releases/download/v1.12.1/ninja-linux-aarch64.zip | Ninja for Linux AArch64 |
| `flutter-linux-arm64-android-only-slim.tar.gz` | https://github.com/MohamedAlkindi/flutter-native-arm64/releases/download/flutter-3.47.1-87-linux/flutter_v3.47.1_linux_arm64_android_web_sdk.tar.gz | ARM64 Flutter bundle trimmed to remove Android x86/x64, iOS, desktop, examples, and source directories. Keeps caches required for Android ARM/ARM64 and Linux ARM64 builds; final size is about 848 MB. |
| `android-tools_aarch64.deb` | https://packages.termux.dev/apt/termux-main/pool/main/a/android-tools/android-tools_36.0.1%2Breally35.0.2_aarch64.deb | Termux AArch64 `adb`; SHA-256 `82e48bf8038250fb0997b1f2cf5f780730104f2544a5532298c453d94cfe1537` |
| `jadx-1.5.0.zip` | https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip | Skylot JADX-CLI Java decompiler engine and launcher |
| `apktool_2.10.0.jar` | https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar | Apktool APK resource and Smali processing toolkit |
| `dex-tools-v2.4.zip` | https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip | pxb1988 dex2jar and smali/baksmali conversion tools |
| `ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz` | https://github.com/BurntSushi/ripgrep/releases/download/15.2.0/ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz | BurntSushi Ripgrep (`rg`), a statically linked single-binary code search tool for Linux AArch64 |
| `rust-1.85.0-aarch64-unknown-linux-gnu.tar.gz` | https://static.rust-lang.org/dist/rust-1.85.0-aarch64-unknown-linux-gnu.tar.gz | Standalone Rust 1.85.0 ARM64 toolchain, including rustc, cargo, and host standard libraries |
| `rust-std-1.85.0-aarch64-linux-android.tar.gz` | https://static.rust-lang.org/dist/rust-std-1.85.0-aarch64-linux-android.tar.gz | Rust cross-compilation target libraries for aarch64-linux-android |
| `uber-apk-signer-1.3.0.jar` | https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar | Standalone one-click APK alignment and v1/v2/v3/v4 signing tool (optional) |
| `baksmali-3.0.8.jar` | https://github.com/google/smali/releases/download/v3.0.8/baksmali-3.0.8.jar | Original Google baksmali standalone disassembler JAR (optional fallback) |
| `smali-3.0.8.jar` | https://github.com/google/smali/releases/download/v3.0.8/smali-3.0.8.jar | Original Google smali standalone assembler JAR (optional fallback) |

## Destination

The final directory must be:

```text
D:\work\taixu\assets\plugins\android-suite-offline\payload\archives\
```

PowerShell example:

```powershell
$archiveDir = 'D:\work\taixu\assets\plugins\android-suite-offline\payload\archives'
New-Item -ItemType Directory -Force $archiveDir | Out-Null
Move-Item 'D:\Downloads\OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz' "$archiveDir\jdk-17-aarch64-linux.tar.gz"
Move-Item 'D:\Downloads\flutter_v3.47.1_linux_arm64_android_web_sdk.tar.gz' "$archiveDir\flutter-source-arm64.tar.gz"
```

After downloading, do not extract the archives. Place the original archives directly in `payload/archives/`. `install-android-suite.sh` extracts them under `/opt` inside PRoot.

## Verify downloads

```powershell
Get-FileHash "$archiveDir\jdk-17-aarch64-linux.tar.gz" -Algorithm SHA256
Get-FileHash "$archiveDir\android-ndk-r29-aarch64.tar.gz" -Algorithm SHA256
Get-FileHash "$archiveDir\android-sdk-tools-static-aarch64.zip" -Algorithm SHA256
Get-FileHash "$archiveDir\android-tools_aarch64.deb" -Algorithm SHA256
```

If direct GitHub access fails, you may switch only to the `ghfast.top` proxy; filenames and SHA-256 values must remain unchanged. Do not replace these with same-named `x86_64`, `amd64`, or `linux-x64` resources.
