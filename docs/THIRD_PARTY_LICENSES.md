# Third-Party Components and License Inventory

This document tracks third-party components used directly, downloaded online, or packaged with the APK. It is a pre-distribution review checklist, not legal advice. Before each release, re-check upstream licenses, NOTICE files, and redistribution terms for the exact versions being shipped.

| Component | Purpose | Source/version | License and distribution notes |
|---|---|---|---|
| Debian / Ubuntu ARM64 RootFS | Linux userspace on Android | Downloaded through the proot-distro 5.9.0 OCI flow from a Docker/OCI Registry | Each distribution and package has its own license. RootFS is not bundled into the APK; provide appropriate copyright and license-access information before release. |
| Termux PRoot | Android ARM64 PRoot launcher | Binary package from the Termux main repository; version and SHA-256 are tracked by ProotInstaller | Verify the license, NOTICE, and source availability for the exact binary package. The APK carries only the current ARM64 build. |
| libandroid-shmem.so / libtalloc.so | Dynamic dependencies for PRoot | Termux packages matching the PRoot build | A shared-library extension does not imply unrestricted redistribution. Verify upstream package license texts before release. |
| zstd-jni | Extract Android ARM64 .tar.zst archives | Maven Central, 1.5.5-4 AAR | Distribute according to the upstream zstd-jni license and NOTICE. |
| RTK | Reduces output from safe foreground Agent commands | rtk-ai/rtk v0.47.0; bundled aarch64-unknown-linux-gnu executable; archive SHA-256 is recorded in the repository | Apache License 2.0; full license is bundled at assets/licenses/rtk-LICENSE. Used only inside the Linux/glibc ARM64 sandbox; unsupported commands fall back to the original command. |
| Node.js ARM64 Runtime | Node 22 fallback needed by OpenClaw | Official Node.js v22.22.3 Linux ARM64 tar.xz; SHA-256 pinned in RuntimeBinaryInstaller | Official Node.js license/NOTICE. Downloaded online, not bundled in the APK. Preserve copyright and source information for the exact version. |
| lzhiyong Android SDK Tools | ARM64 aapt, aapt2, aidl, and zipalign | Static AArch64 archive 35.0.2; URL and SHA-256 pinned in setup_android_core.sh | Downloaded online, not bundled in the APK. Verify upstream Apache/AOSP LICENSE, NOTICE, and redistribution requirements. |
| lzhiyong/termux-ndk | Linux AArch64 NDK host toolchain for Android/Flutter builds in the sandbox | r29 / NDK 29.0.14206865; archive digest pinned in setup_termux_ndk.sh | Downloaded online, not bundled in the APK. Check LICENSE, NOTICE, source, and modification notices for the pinned version. |
| OkHttp | HTTPS downloads and Registry requests | Maven Central, 4.12.0 | Apache License 2.0; include required Apache copyright and NOTICE text. |
| AndroidX / Jetpack Compose Material 3 | Android UI and platform integration | Google Maven; versions in Gradle version catalogs | Follow each upstream artifact's license and notice requirements. |

## Release checklist

- Re-check the exact dependency versions used by the release build.
- Include license and NOTICE files required for bundled binaries and libraries.
- Record the upstream source and SHA-256 of downloaded executables and archives.
- Verify whether each downloaded component may be redistributed and whether source or modification notices are required.
- Keep RootFS distribution/package licenses accessible even though RootFS is downloaded on demand.
- Revisit this inventory whenever dependencies, bundled binaries, or installer download URLs change.
