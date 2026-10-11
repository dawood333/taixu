Phase 1 runtime payloads

The Linux ARM64 rootfs is downloaded online by default and is not bundled in the APK. Android 10+ does not allow an app to execute ELF binaries from writable private directories. Therefore, the APK contains only the small ARM64 PRoot executable, its external loader, and their dynamic dependencies. The Linux system is installed during first launch through the `proot-distro 5.9.0` OCI workflow.

The installer selects the `linux/arm64` manifest from a Docker/OCI registry, caches layers that pass digest verification in the app's private directory, merges them according to OCI whiteout rules, and then atomically enables the RootFS.
The PRoot executable and loader must come from the same official Termux package. Before building, run `tools/prepare-proot-runtime.ps1` to verify the package SHA-256 and prepare both files.
