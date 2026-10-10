# TaiXu — Development and Automation Command Reference

---

## 1. Build prerequisites

- **Android SDK:** The main project requires Android SDK Platform 37.1 (required by the A2UI alpha dependency); targetSdk remains 37. If SDK licenses have been accepted, Gradle can automatically install missing platforms.

- **JDK environment variable (Windows PowerShell):** Set `JAVA_HOME` to the JBR bundled with Android Studio (Java 25 / 17+) before running Gradle tasks:
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  # Or a custom install path, e.g. D:\Program Files\Android\Android Studio\jbr
  ```

---

## 2. Core build and test commands

```powershell
# 1. Run all unit tests (full-repository regression)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat test --console=plain

# 2. Run unit tests for one module
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :feature:settings:testDebugUnitTest --console=plain

# 3. Compile Kotlin only (fast syntax and type check)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin --console=plain

# 4. Fast compile validation for one module
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :harness:compileDebugKotlin --console=plain -q

# 5. Build and package the Debug APK
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain

# 6. Verify architecture dependency boundaries (single source of truth: root architecture-policy.json)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat architectureCheck --console=plain
# Checks: module dependency allowlist (including reverse dependencies), acyclic dependency graph, import denylist
#          (pure Kotlin model layer / direct DAO access), file-size ratchet (maxFileLines=400; existing oversized
#          files are registered in .architecture-baseline.json and may only shrink).

# 6.1 Sync the file-size ratchet baseline (reduce limits / reject increases / remove stale entries; run after shrinking code)
$env:JAVA_HOME="C:\Program Files\Android\Studio\jbr"; .\gradlew.bat architectureBaselineSync --console=plain
# Note: when adding a module or changing module dependencies, update the requires entries in architecture-policy.json
# or the architecture gate will fail.

# 7. Validate Harness built-in tool contracts and execution policy only
$env:JAVA_HOME="C:\Program Files\Android\Studio\jbr"; .\gradlew.bat :harness:testDebugUnitTest --tests "top.wkbin.taixu.harness.*" --console=plain
```

---

## 3. Device deployment and live-log debugging

```powershell
# 1. Install the Debug APK on a connected physical device or emulator
adb install -r app/build/outputs/apk/debug/taixu-v0.5.0-debug.apk

# 2. Launch TaiXu's main Activity
adb shell am start -n top.wkbin.taixu/.MainActivity

# 3. Filter live logs for the TaiXu runtime and Agent core
adb logcat -s TaiXu:V HarnessLoop:V ProotProcess:V
```

## 4. Koin dependency-graph regression

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "top.wkbin.taixu.di.*" --console=plain
```

After changing dependency registration, run the full unit-test suite and `:app:assembleDebug`. Dependency injection has migrated to the Koin DSL.
