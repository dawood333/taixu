# Baseline Profile Startup Optimization Guide

> Phase two of TaiXu cold-start optimization: precompile hot methods at install time (AOT) to reduce JIT compilation during the first launch.

## How it works

The APK bundles `assets/dexopt/baseline.prof` (compiled from `app/src/release/generated/baselineProfiles/`). After installation, `androidx.profileinstaller` submits it to ART in the background for precompilation. It covers:

- The full startup path from `Application.onCreate` through Koin graph creation to the first Compose screen.
- The first-frame LazyColumn scrolling path.

## Module structure

| Path | Responsibility |
| :--- | :--- |
| `baselineprofile/` | Generator module (`com.android.test` + macrobenchmark), including collection scenarios |
| `baselineprofile/.../StartupBaselineProfile.kt` | Collection scenario: cold start + light scrolling on the home screen |
| `app/src/release/generated/baselineProfiles/` | Generated output, **committed to version control** |
| `app/build.gradle.kts` | Consumer wiring: plugin + `baselineProfile(project(":baselineprofile"))` |

## Re-collect the profile (after major UI or startup-path changes)

Prerequisite: a connected, unlocked device or emulator (physical device or AVD).

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:generateBaselineProfile --console=plain
```

Success indicators: `app/src/release/generated/baselineProfiles/*.txt` is updated and the test reports `1/1 completed (0 failed)`.

### Select a target when multiple devices are connected

```powershell
$env:ANDROID_SERIAL = "emulator-5554"   # Or a specific device serial
```

### If no device is available: start a headless AVD manually

```powershell
# One-time setup (if this repository already has the android-37.1 google_apis_playstore x86_64 image, use it directly)
powershell -NoProfile -Command "Start-Process -FilePath \"$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe\" -ArgumentList '@<avd-name>','-no-window','-no-audio','-no-boot-anim'"
adb -s emulator-5554 shell settings put secure user_setup_complete 1
adb -s emulator-5554 shell settings put global device_provisioned 1
```

An x86_64 emulator can run an arm64-only app (emulator 37+ performs automatic binary translation); TaiXu's arm64-v8a-only target is not a problem.

## Known pitfalls

1. **USB security hardening on some domestic ROMs, including vivo,** may block instrumentation with `Calling from not trusted UID!`. Symptoms include `Failed to install split APK(s)` or the instrumentation process exiting immediately. Such devices cannot be used for collection; use an emulator instead.
2. Collection uses a non-minified variant. If the release main thread crashes (for example, due to a regex issue exposed only after R8), collection fails with `Process crashed`. Fix the crash first.
3. Force-killing the Gradle daemon can leave a corrupted KSP cache (for example, `NoSuchFileException ... kspCaches\...\*_Factory.java`). Delete the affected module's `build/kspCaches` directory and rerun.

## Verify the benefit

```powershell
adb install -r app/build/outputs/apk/release/taixu-v0.6.0-release.apk
adb shell am start -W top.wkbin.taixu/.MainActivity   # Compare TotalTime
adb logcat -s TaiXuStartup                            # Time until splash dismissal
```

The Koin migration removed profile entries for old DI-generated classes. Re-collect the complete startup hot path on an ARM64 device using this guide; simply replacing class names is not sufficient.
