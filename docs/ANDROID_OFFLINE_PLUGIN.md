# Building a Fully Offline Android Toolchain Plugin

This guide describes packaging the Android SDK, Build Tools, ARM64 AAPT2, NDK, JDK, Gradle, ADB, and the Android-only Flutter SDK into one .txplugin package for offline installation. It does not include Web, iOS, Windows, macOS, or Linux Desktop build assets. iOS builds are not supported inside Linux ARM64 PRoot.

## 1. Current implementation

The built-in android-suite bundle is currently installed through BuiltinPluginBundles and ToolManager.batchInstallComponents(); its standard setup scripts download Android, Gradle, NDK, and Flutter assets.

A local .txplugin follows ToolRegistry → ToolManager → GenericRecipeInstaller. For a fully offline package, create a separate monolithic local plugin such as android-suite-offline with an install script that deploys all included assets. Do not simply copy the existing network-dependent scripts into the package.

Relevant implementation:
- core/model/ToolManifest.kt
- tools/core/tools/ToolRegistry.kt
- tools/runtime/tools/GenericRecipeInstaller.kt
- runtime/scripts/RuntimeAssetSynchronizer.kt

## 2. Suggested package layout

Include manifest.json and payload/ at the ZIP root. Store archives under payload/archives, install/verify/uninstall scripts under payload/scripts, configuration templates under payload/config, and deployable binaries under payload/bin.

The importer permits only manifest.json and payload/ at the package root. Reject absolute paths, traversal paths, and malformed manifests. The manifest limit is 1 MiB and total uncompressed content is limited to 8 GiB. Leave enough free space for the downloaded package, app-private copy, distribution payload, extraction staging, and final install paths.

## 3. Assets to bundle

A complete offline Android toolchain may include:

- Android SDK Platform 34 and its android.jar/core-for-system-modules.jar.
- Android Build Tools metadata and Java-side tools.
- ARM64-native AAPT/AAPT2, AIDL, and zipalign where required by the Linux ARM64 environment.
- A pinned Gradle distribution.
- An ARM64-compatible NDK toolchain.
- A compatible JDK and ADB.
- Android-only Flutter SDK assets if Flutter builds are required.
- Accepted SDK licenses and the configuration required for non-interactive builds.

Do not assume that Android host tools downloaded for x86_64 Linux can execute on ARM64. Pin each large archive with SHA-256 and verify it before extraction.

## 4. Installation design

1. Validate the package and manifest.
2. Verify each archive digest.
3. Copy assets into a staging directory on the same filesystem as the final destination.
4. Extract archives without assuming optional commands such as unzip, xz, file, readelf, jq, Python, or Node are present.
5. Explicitly restore executable permissions after ZIP extraction.
6. Verify Java, Gradle launcher JAR, android.jar, AAPT/AAPT2, NDK binaries, and required SDK directories.
7. Commit the staged toolchain atomically only after checks pass.
8. On failure, clean staging and preserve the previously working installation.

The installer provides TAIXU_TOOL_ID, TAIXU_TOOL_DIR, TAIXU_TOOL_DATA, and TAIXU_PLUGIN_PAYLOAD for local plugins. Keep program files separate from persistent user data.

## 5. Gradle and NDK configuration

Use the project's configured Android toolchain policy. The NDK should be injected through android.ndkPath. Remove stale ndk.dir from local.properties, but do not write a second NDK locator, which can cause AGP conflicts.

Use bounded workers and disable the Gradle daemon for constrained sandbox builds. Keep downloads and repair logic out of the actual build script; perform one-time provisioning in the plugin setup phase.

## 6. Offline verification

After installation, disconnect networking and verify:

- Java and Gradle start.
- The Android SDK platform and Build Tools are discoverable.
- AAPT/AAPT2 and zipalign execute on ARM64.
- NDK compiler and linker binaries can start and load required libraries.
- A minimal Android project can compile and package.
- If Flutter is included, the Flutter tool starts and a minimal Android target can build.
- Re-running setup is idempotent and does not redownload already validated assets.

## 7. Release checklist

- Pin versions and SHA-256 digests for all large assets.
- Review redistribution terms and retain third-party license notices.
- Do not include secrets, personal SDK configuration, or local absolute paths.
- Verify ABI compatibility, executable bits, and runtime libraries.
- Test fresh installation, repeated installation, interrupted installation, recovery, and uninstall on an ARM64 device.
- Account for every disk copy and ensure rollback claims match the directories actually snapshotted.
