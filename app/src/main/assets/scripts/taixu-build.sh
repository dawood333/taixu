#!/bin/sh
set -u

workshop_java_home="${JAVA_HOME:-}"
workshop_android_home="${ANDROID_HOME:-}"
workshop_android_sdk_root="${ANDROID_SDK_ROOT:-}"
workshop_gradle_home="${GRADLE_HOME:-}"
workshop_flutter_home="${FLUTTER_HOME:-}"
workshop_ndk_path="${TAIXU_NDK_PATH:-}"
workshop_android_ndk_home="${ANDROID_NDK_HOME:-}"
workshop_aapt2_path="${TAIXU_AAPT2_PATH:-}"
workshop_cmake_home="${TAIXU_CMAKE_HOME:-}"
workshop_ninja_home="${TAIXU_NINJA_HOME:-}"
workshop_gradle_user_home="${GRADLE_USER_HOME:-}"
workshop_pub_cache="${PUB_CACHE:-}"
workshop_tool_dir="${TAIXU_TOOL_DIR:-}"
if test -f /etc/profile.d/taixu-android.sh; then . /etc/profile.d/taixu-android.sh; fi
test -z "$workshop_java_home" || JAVA_HOME="$workshop_java_home"
test -z "$workshop_android_home" || ANDROID_HOME="$workshop_android_home"
test -z "$workshop_android_sdk_root" || ANDROID_SDK_ROOT="$workshop_android_sdk_root"
test -z "$workshop_gradle_home" || GRADLE_HOME="$workshop_gradle_home"
test -z "$workshop_flutter_home" || FLUTTER_HOME="$workshop_flutter_home"
test -z "$workshop_ndk_path" || TAIXU_NDK_PATH="$workshop_ndk_path"
test -z "$workshop_android_ndk_home" || ANDROID_NDK_HOME="$workshop_android_ndk_home"
test -z "$workshop_aapt2_path" || TAIXU_AAPT2_PATH="$workshop_aapt2_path"
test -z "$workshop_cmake_home" || TAIXU_CMAKE_HOME="$workshop_cmake_home"
test -z "$workshop_ninja_home" || TAIXU_NINJA_HOME="$workshop_ninja_home"
test -z "$workshop_gradle_user_home" || GRADLE_USER_HOME="$workshop_gradle_user_home"
test -z "$workshop_pub_cache" || PUB_CACHE="$workshop_pub_cache"
test -z "$workshop_tool_dir" || TAIXU_TOOL_DIR="$workshop_tool_dir"
if test -z "${JAVA_HOME:-}" && test -x /opt/taixu/toolchains/android/jdk/bin/java; then
    JAVA_HOME=/opt/taixu/toolchains/android/jdk
elif test -z "${JAVA_HOME:-}"; then
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
fi
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
GRADLE_HOME="${GRADLE_HOME:-/opt/gradle-8.14.2}"
FLUTTER_HOME="${FLUTTER_HOME:-/opt/flutter}"
if [ -z "${TAIXU_CMAKE_HOME:-}" ] || [ ! -x "$TAIXU_CMAKE_HOME/bin/cmake" ]; then
    if [ -x /opt/taixu/tools/android-ndk/cmake/bin/cmake ]; then
        TAIXU_CMAKE_HOME="/opt/taixu/tools/android-ndk/cmake"
    elif [ -x /opt/taixu/tools/android-suite-offline/cmake/bin/cmake ]; then
        TAIXU_CMAKE_HOME="/opt/taixu/tools/android-suite-offline/cmake"
    else
        TAIXU_CMAKE_HOME="${TAIXU_CMAKE_HOME:-/opt/taixu/tools/android-suite-offline/cmake}"
    fi
fi
if [ -z "${TAIXU_NINJA_HOME:-}" ] || [ ! -x "$TAIXU_NINJA_HOME/ninja" ]; then
    if [ -x /opt/taixu/tools/android-ndk/bin/ninja ]; then
        TAIXU_NINJA_HOME="/opt/taixu/tools/android-ndk/bin"
    elif [ -x /opt/taixu/tools/android-suite-offline/bin/ninja ]; then
        TAIXU_NINJA_HOME="/opt/taixu/tools/android-suite-offline/bin"
    else
        TAIXU_NINJA_HOME="${TAIXU_NINJA_HOME:-/opt/taixu/tools/android-suite-offline/bin}"
    fi
fi
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
TAIXU_TOOL_DIR="${TAIXU_TOOL_DIR:-/opt/taixu/tools}"
export ANDROID_HOME ANDROID_SDK_ROOT GRADLE_HOME JAVA_HOME FLUTTER_HOME TAIXU_CMAKE_HOME TAIXU_NINJA_HOME TAIXU_TOOL_DIR
export GRADLE_USER_HOME
export PATH="/opt/taixu/bin:$JAVA_HOME/bin:$GRADLE_HOME/bin:$FLUTTER_HOME/bin:$TAIXU_CMAKE_HOME/bin:$TAIXU_NINJA_HOME:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
# Internal build scripts may call `gradle`/`flutter` by name. Mark this
# process so the console wrappers pass those calls to the already-validated
# fixed toolchain instead of recursively invoking this entrypoint.
export TAIXU_BUILD_ENGINE=1

fail() { echo "[TaiXu Build] ERROR: $*" >&2; exit 10; }
warn() { echo "[TaiXu Build] WARN: $*" >&2; }
need_file() { test -e "$1" || fail "Missing $2: $1"; }
need_exec() { test -x "$1" || fail "Missing executable $2: $1"; }
verify_artifact() {
    verifier=/opt/taixu/scripts/taixu-build-verify.sh
    need_exec "$verifier" "APK ABI verifier"
    /bin/sh "$verifier" "$1" || fail "APK artifact verification failed; refusing to export/install"
}

check_elf_machine() {
    path="$1"
    expected="$2"
    label="$3"
    test -f "$path" || fail "$label does not exist: $path"
    machine=$(od -An -t x1 -j 18 -N 2 "$path" 2>/dev/null | tr -d '[:space:]')
    test "$machine" = "$expected" || fail "$label ELF architecture mismatch (machine=$machine, expected=$expected)"
}

# The JDK launcher must resolve to a real ELF binary. A wrapper script whose
# exec target points back into the same resolution chain becomes an infinite
# exec loop under PRoot (every exec goes through ptrace translation and only
# burns CPU). Reject non-ELF launchers BEFORE the JVM starts, then verify the
# actual JVM shared library so the arch check works for symlinked JDKs.
java_arch_machine() {
    bin="$1"
    real=$(readlink -f "$bin" 2>/dev/null || echo "$bin")
    magic=$(od -An -t x1 -N 4 "$real" 2>/dev/null | tr -d '[:space:]')
    if [ "$magic" != "7f454c46" ]; then echo not_elf; return 0; fi
    home=$(dirname "$(dirname "$real")")
    jvm_lib=$(find "$home" \( -type f -o -type l \) -name libjvm.so -print -quit 2>/dev/null || true)
    test -n "$jvm_lib" || { echo unreadable; return 0; }
    od -An -t x1 -j 18 -N 2 "$jvm_lib" 2>/dev/null | tr -d '[:space:]'
}

check_java_arch() {
    bin="$1"
    expected="$2"
    label="$3"
    test -x "$bin" || fail "$label does not exist: $bin"
    machine=$(java_arch_machine "$bin")
    test "$machine" = "$expected" || fail "$label ELF architecture mismatch (machine=$machine, expected=$expected)"
}

detect_project() {
    project="$1"
    if test -f "$project/pubspec.yaml"; then echo flutter; return; fi
    if test -f "$project/settings.gradle" || test -f "$project/settings.gradle.kts" || test -f "$project/gradlew"; then echo android; return; fi
    echo unknown
}

keep_project_arm64_only() {
    project="$1"
    lib_root="$project/lib"
    test -d "$lib_root" || return 0
    for abi_dir in "$lib_root"/*; do
        test -d "$abi_dir" || continue
        abi=$(basename "$abi_dir")
        case "$abi" in
            arm64-v8a) ;;
            armeabi-v7a|x86|x86_64)
                echo "[TaiXu Build] Removing non-ARM64 output directory: lib/$abi"
                rm -rf -- "$abi_dir"
                ;;
        esac
    done
}

check_project_toolchain_declarations() {
    project="$1"
    ndk_home="${TAIXU_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/taixu/toolchains/android/ndk}}"
    expected_ndk=$(sed -n 's/^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_home/source.properties" 2>/dev/null | head -n 1 || true)
    declared_ndk=$(grep -RhsE 'ndkVersion[^0-9]*[0-9]+(\.[0-9]+)+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null |
        sed -n 's/.*ndkVersion[^0-9]*\([0-9][0-9.]*\).*/\1/p' | head -n 1 || true)
    if test -n "$declared_ndk" && test -n "$expected_ndk" && test "$declared_ndk" != "$expected_ndk"; then
        warn "Project declares NDK $declared_ndk; build will use the NDK currently configured in Workshop: $expected_ndk"
    fi
    declared_cmake=$(grep -RhsE 'cmake[^\n]*version[^0-9]*[0-9]+(\.[0-9]+)+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null |
        sed -n 's/.*version[^0-9]*\([0-9][0-9.]*\).*/\1/p' | head -n 1 || true)
    expected_cmake=$(${TAIXU_CMAKE_HOME:-/opt/taixu/tools/android-suite-offline/cmake}/bin/cmake --version 2>/dev/null |
        sed -n 's/^cmake version[[:space:]]*//p' | head -n 1 || true)
    if test -n "$declared_cmake" && test -n "$expected_cmake" && test "$declared_cmake" != "$expected_cmake"; then
        warn "Project declares CMake $declared_cmake; build will use the CMake version currently configured in Workshop: $expected_cmake"
    fi
}

doctor() {
    project="${1:-}"
    test -n "$project" || fail "Usage: taixu-build doctor <project-dir>"
    test -d "$project" || fail "Project directory does not exist: $project"
    keep_project_arm64_only "$project"
    need_exec /bin/sh "POSIX Shell"
    need_exec "$JAVA_HOME/bin/java" "JDK 17"
    kind=$(detect_project "$project")
    check_project_toolchain_declarations "$project"
    analyze_args=""
    test "${TAIXU_OFFLINE:-0}" = 1 && analyze_args="--offline"
    need_file "$ANDROID_HOME/platforms/android-34/android.jar" "Android Platform 34"
    has_native=0
    if test -f "$project/CMakeLists.txt" || test -f "$project/app/CMakeLists.txt" || test -d "$project/app/src/main/cpp" || test -d "$project/app/src/main/jni"; then
        has_native=1
    elif grep -Eq 'externalNativeBuild|ndkBuild|cmake[[:space:]]*\{' "$project/build.gradle" "$project/app/build.gradle" "$project/build.gradle.kts" "$project/app/build.gradle.kts" 2>/dev/null; then
        has_native=1
    fi

    if [ "$has_native" = 1 ]; then
        need_exec "$TAIXU_CMAKE_HOME/bin/cmake" "CMake"
        need_exec "$TAIXU_NINJA_HOME/ninja" "Ninja"
    fi
    aapt2="${TAIXU_AAPT2_PATH:-$ANDROID_HOME/build-tools/35.0.0/aapt2}"
    need_exec "$aapt2" "ARM64 AAPT2"
    check_java_arch "$JAVA_HOME/bin/java" b700 "JDK 17"
    check_elf_machine "$aapt2" b700 "AAPT2"
    TAIXU_AAPT2_PATH="$aapt2"
    export TAIXU_AAPT2_PATH
    ndk="${TAIXU_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/taixu/toolchains/android/ndk}}"
    if [ "$has_native" = 1 ] || [ -f "$ndk/source.properties" ]; then
        need_file "$ndk/source.properties" "Pinned ARM64 NDK"
        ndk_clang=$(find "$ndk/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null)
        ndk_strip=$(find "$ndk/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit 2>/dev/null)
        need_exec "$ndk_clang" "ARM64 NDK clang"
        need_exec "$ndk_strip" "ARM64 NDK llvm-strip"
        check_elf_machine "$ndk_clang" b700 "NDK clang"
        check_elf_machine "$ndk_strip" b700 "NDK llvm-strip"
        TAIXU_NDK_PATH="$ndk"
        ANDROID_NDK_HOME="$ndk"
        ANDROID_NDK_ROOT="$ndk"
        export TAIXU_NDK_PATH ANDROID_NDK_HOME ANDROID_NDK_ROOT
        managed_ndk_policy=/opt/taixu/scripts/taixu-android-ndk.gradle
        if test -f "$managed_ndk_policy"; then
            mkdir -p "$GRADLE_USER_HOME/init.d"
            cp "$managed_ndk_policy" "$GRADLE_USER_HOME/init.d/taixu-android-ndk.gradle"
        fi
    fi
    gradle_properties="$GRADLE_USER_HOME/gradle.properties"
    touch "$gradle_properties"
    grep -Fqx 'android.builder.sdkDownload=false' "$gradle_properties" 2>/dev/null ||
        printf '%s\n' 'android.builder.sdkDownload=false' >> "$gradle_properties"
    # Release 签名策略：仅在宿主注入 TAIXU_KEYSTORE_* 环境变量时生效，debug 构建零影响。
    if test -f /opt/taixu/scripts/taixu-release-signing.gradle; then
        cp /opt/taixu/scripts/taixu-release-signing.gradle "$GRADLE_USER_HOME/init.d/taixu-release-signing.gradle"
    fi
    grep -Fqx 'android.builder.sdkDownload=false' "$gradle_properties" 2>/dev/null ||
        fail "Gradle SDK auto-download is enabled and may download x86_64 host tools"
    wrapper_project="$project"
    test "$kind" = flutter && wrapper_project="$project/android"
    if test -f "$wrapper_project/gradlew" || test -d "$wrapper_project/gradle/wrapper"; then
        test -f "$wrapper_project/gradlew" -a -f "$wrapper_project/gradle/wrapper/gradle-wrapper.jar" -a \
            -f "$wrapper_project/gradle/wrapper/gradle-wrapper.properties" ||
            warn "Project Gradle Wrapper is incomplete; using local Gradle 8.14.2"
    fi
    test -x "$GRADLE_HOME/bin/gradle" -o -d "$GRADLE_HOME/lib" -o -x "$wrapper_project/gradlew" -o -n "$(command -v gradle 2>/dev/null || true)" ||
        fail "Gradle 8.14.2 or the project Gradle Wrapper was not found"
    test "$kind" != android || test -f "$project/settings.gradle" -o -f "$project/settings.gradle.kts" ||
        fail "Not a recognized Android Gradle project (settings.gradle/settings.gradle.kts is missing)"
    wrapper="$wrapper_project/gradle/wrapper/gradle-wrapper.properties"
    if test -f "$wrapper"; then
        wrapper_version=$(sed -n 's#.*gradle-\([0-9][0-9.]*\)-bin\.zip.*#\1#p' "$wrapper" | head -n 1)
        if test -n "$wrapper_version" && test "$wrapper_version" != "8.14.2"; then
            warn "Project Gradle Wrapper=$wrapper_version; local baseline=8.14.2. The TaiXu build entry point will prefer local Gradle."
        fi
    fi
    compile_sdk=$(grep -RhsE 'compileSdk(Version)?[[:space:]]*[=( ]+[0-9]+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts 2>/dev/null | grep -Eo '[0-9]+' | head -n 1)
    if test -n "$compile_sdk" && test "$compile_sdk" -gt 34; then
        fail "Project compileSdk=$compile_sdk exceeds local Android Platform 34. Ask the Agent to align the project or install the matching ARM64 platform resources first."
    fi
    if test "$kind" = flutter; then
        need_exec "$FLUTTER_HOME/bin/flutter" "Flutter SDK"
        need_exec "$FLUTTER_HOME/bin/cache/dart-sdk/bin/dart" "Dart SDK"
        check_elf_machine "$FLUTTER_HOME/bin/cache/dart-sdk/bin/dart" b700 "Dart SDK"
        engine_dir="$FLUTTER_HOME/bin/cache/artifacts/engine"
        native_gen_snapshot="$engine_dir/linux-arm64/gen_snapshot"
        if test -f "$native_gen_snapshot"; then
            check_elf_machine "$native_gen_snapshot" b700 "Flutter gen_snapshot"
            for mode in release profile; do
                target_dir="$engine_dir/android-arm64-$mode/linux-arm64"
                mkdir -p "$target_dir" 2>/dev/null || true
                if test ! -e "$target_dir/gen_snapshot"; then
                    ln -sf "$native_gen_snapshot" "$target_dir/gen_snapshot" 2>/dev/null || true
                fi
            done
        fi
        test -d "$project/android" || fail "Flutter project is missing the Android host directory"
    elif test "$kind" = unknown; then
        warn "Unable to identify project type; checking only the general Android toolchain"
    fi
    if test "${TAIXU_OFFLINE:-0}" = 1; then
        if test "$kind" = flutter; then
            test -d "${PUB_CACHE:-/opt/taixu/cache/flutter-pub}/hosted" ||
                fail "Flutter Pub cache is missing in offline mode; resolve dependencies online first"
        else
            test -d "${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1" ||
                fail "Gradle project dependency cache is missing in offline mode; resolve dependencies online first"
        fi
    fi
    if test -x /opt/taixu/scripts/taixu-build-analyze.sh; then
        /bin/sh /opt/taixu/scripts/taixu-build-analyze.sh "$project" "$analyze_args" ||
            fail "Project compatibility analysis failed"
    fi
    echo "[TaiXu Build] OK: ARM64 build environment is ready ($kind)"
}

qemu_doctor() {
    project="$1"
    compat=/opt/taixu/compat/x86_64
    test "$(uname -m)" = x86_64 || fail "--qemu must be run by the TaiXu app in an isolated QEMU PRoot session; a regular ARM64 terminal cannot switch directly"
    need_exec "$compat/jdk-17/bin/java" "QEMU x86_64 JDK"
    need_exec "$compat/android-sdk/build-tools/35.0.0/aapt2" "QEMU x86_64 AAPT2"
    check_java_arch "$compat/jdk-17/bin/java" 3e00 "QEMU JDK"
    check_elf_machine "$compat/android-sdk/build-tools/35.0.0/aapt2" 3e00 "QEMU AAPT2"
    need_file "$compat/android-sdk/platforms/android-34/android.jar" "QEMU Android Platform 34"
    need_file "$compat/android-sdk/build-tools/35.0.0/lib/d8.jar" "QEMU Android Build-Tools 35"
    kind=$(detect_project "$project")
    wrapper_project="$project"
    test "$kind" = flutter && wrapper_project="$project/android"
    if test -f "$wrapper_project/gradlew" || test -d "$wrapper_project/gradle/wrapper"; then
        test -f "$wrapper_project/gradlew" -a -f "$wrapper_project/gradle/wrapper/gradle-wrapper.jar" -a \
            -f "$wrapper_project/gradle/wrapper/gradle-wrapper.properties" ||
            warn "Project Gradle Wrapper is incomplete; using Gradle 8.14.2 from the QEMU environment"
    fi
    test -x "$compat/gradle-8.14.2/bin/gradle" -o -d "$compat/gradle-8.14.2/lib" -o -f "$wrapper_project/gradle/wrapper/gradle-wrapper.jar" ||
        fail "QEMU environment is missing Gradle 8.14.2 or the project Wrapper"
    test -d "$project" || fail "Project directory does not exist: $project"
    if test "$kind" = flutter; then
        need_exec "$compat/flutter/bin/flutter" "QEMU Flutter SDK"
        need_exec "$compat/flutter/bin/cache/dart-sdk/bin/dart" "QEMU Dart SDK"
        check_elf_machine "$compat/flutter/bin/cache/dart-sdk/bin/dart" 3e00 "QEMU Dart SDK"
        test -d "$project/android" || fail "Flutter project is missing the Android host directory"
    elif test "$kind" = android; then
        test -f "$project/settings.gradle" -o -f "$project/settings.gradle.kts" ||
            fail "Not a recognized Android Gradle project (settings.gradle/settings.gradle.kts is missing)"
    else
        fail "Unable to identify project type"
    fi
    compile_sdk=$(grep -RhsE 'compileSdk(Version)?[[:space:]]*[=( ]+[0-9]+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null | grep -Eo '[0-9]+' | head -n 1)
    if test -n "$compile_sdk" && test "$compile_sdk" -gt 34; then
        fail "Project compileSdk=$compile_sdk exceeds Android Platform 34 in the QEMU environment. Ask the Agent to align the project or install the matching platform resources first."
    fi
    echo "[TaiXu Build] OK: isolated QEMU x86_64 build environment is ready"
}

run_android() {
    project="$1"
    shift
    qemu=0
    offline="${TAIXU_OFFLINE:-0}"
    task=assembleDebug
    for arg in "$@"; do
        if test "$arg" = "--qemu"; then qemu=1; elif test "$arg" = "--offline"; then offline=1; else task="$arg"; fi
    done
    keep_project_arm64_only "$project"
    if test "$qemu" = 1; then
        qemu_doctor "$project" || return $?
        export TAIXU_BUILD_ENGINE=1
        if test "$offline" = 1; then export TAIXU_OFFLINE=1; fi
        /bin/sh /opt/taixu/scripts/build_android_qemu.sh "$project" "$task"; status=$?
        test "$status" -eq 0 || return "$status"
        apk=$(find "$project" -type f -name '*.apk' ! -name '*unaligned*' -exec ls -t {} + 2>/dev/null | head -n 1)
        test -n "$apk" || fail "QEMU build completed but no APK was found"
        verify_artifact "$apk"
        return 0
    fi
    doctor "$project" || return $?
    # The normal path is deliberately ARM64 and does not silently switch ABI.
    export TAIXU_BUILD_ENGINE=1
    if test "$offline" = 1; then export TAIXU_OFFLINE=1; fi
    /bin/sh /opt/taixu/scripts/build_android.sh "$project" "$task"; status=$?
    test "$status" -eq 0 || return "$status"
    apk=$(find "$project" -type f -name '*.apk' ! -name '*unaligned*' -exec ls -t {} + 2>/dev/null | head -n 1)
    test -n "$apk" || fail "Build completed but no APK was found"
    verify_artifact "$apk"
}

run_flutter() {
    project="$1"
    shift
    qemu=0
    offline="${TAIXU_OFFLINE:-0}"
    target="apk --debug --target-platform android-arm64"
    custom=""
    for arg in "$@"; do
        if test "$arg" = "--qemu"; then qemu=1; elif test "$arg" = "--offline"; then offline=1; else custom="$custom $arg"; fi
    done
    test -z "$custom" || target=$(printf '%s' "$custom" | sed 's/^ //')
    keep_project_arm64_only "$project"
    if test "$qemu" = 1; then
        qemu_doctor "$project" || return $?
        export TAIXU_BUILD_ENGINE=1
        if test "$offline" = 1; then export TAIXU_OFFLINE=1; fi
        /bin/sh /opt/taixu/scripts/build_flutter_qemu.sh "$project"; status=$?
        test "$status" -eq 0 || return "$status"
        apk=$(find "$project" -type f -name '*.apk' ! -name '*unaligned*' -exec ls -t {} + 2>/dev/null | head -n 1)
        test -n "$apk" || fail "QEMU Flutter Build completed but no APK was found"
        verify_artifact "$apk"
        return 0
    fi
    doctor "$project" || return $?
    export TAIXU_BUILD_ENGINE=1
    if test "$offline" = 1; then export TAIXU_OFFLINE=1; fi
    /bin/sh /opt/taixu/scripts/build_flutter.sh "$project" "$target"; status=$?
    test "$status" -eq 0 || return "$status"
    apk=$(find "$project" -type f -name '*.apk' ! -name '*unaligned*' -exec ls -t {} + 2>/dev/null | head -n 1)
    test -n "$apk" || fail "Flutter Build completed but no APK was found"
    verify_artifact "$apk"
}

command="${1:-}"
shift || true
case "$command" in
    analyze)
        project="${1:-}"
        test -n "$project" || fail "Usage: taixu-build analyze <project-dir> [--offline]"
        shift || true
        exec /bin/sh /opt/taixu/scripts/taixu-build-analyze.sh "$project" "${1:-}"
        ;;
    doctor)
        project="${1:-}"
        if test "${2:-}" = "--qemu"; then qemu_doctor "$project"; else doctor "$project"; fi
        ;;
    android) test "$#" -ge 1 || fail "Usage: taixu-build android <project-dir> [Gradle-task] [--qemu]"; run_android "$@" ;;
    flutter) test "$#" -ge 1 || fail "Usage: taixu-build flutter <project-dir> [Flutter-args] [--qemu]"; run_flutter "$@" ;;
    *) fail "Usage: taixu-build {doctor|android|flutter} ..." ;;
esac
