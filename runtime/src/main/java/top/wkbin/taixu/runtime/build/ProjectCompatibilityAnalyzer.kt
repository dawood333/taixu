package top.wkbin.taixu.runtime.build

import java.io.File
import top.wkbin.taixu.runtime.ProjectType

enum class CompatibilitySeverity {
    INFO,
    WARNING,
    ERROR,
}

data class CompatibilityFinding(
    val id: String,
    val severity: CompatibilitySeverity,
    val message: String,
    val current: String? = null,
    val expected: String? = null,
    val remediation: String? = null,
)

data class ProjectCompatibilityReport(
    val projectPath: String,
    val projectType: ProjectType,
    val filesChecked: List<String>,
    val findings: List<CompatibilityFinding>,
) {
    val canUseArm64Toolchain: Boolean
        get() = findings.none { it.severity == CompatibilitySeverity.ERROR }

    val requiresUserAlignment: Boolean
        get() = findings.any { it.id == "compile_sdk" || it.id == "gradle_wrapper" || it.id == "agp" || it.id == "kotlin" }

    val hasX86NativeInputs: Boolean
        get() = findings.any { it.id == "x86_abi" }
}

/**
 * A conservative, side-effect-free analyzer for third-party Android/Flutter projects.
 * It only reads project files and never rewrites them. The shell doctor remains the
 * authority inside PRoot; this class gives the app and tests a deterministic report model.
 */
object ProjectCompatibilityAnalyzer {
    private val compileSdkPattern = Regex("compileSdk(?:Version)?\\s*[= (:]\\s*(\\d+)")
    private val gradleVersionPattern = Regex("gradle-([0-9]+(?:\\.[0-9]+)*)-(?:bin|all)\\.zip")
    private val agpPattern = Regex("(?:com\\.android\\.tools\\.build:gradle|com\\.android\\.application)[:\\\"' =]+(?:[\\\"']?)([0-9]+(?:\\.[0-9]+)*)")
    private val kotlinPattern = Regex("(?:kotlin(?:Version)?|org\\.jetbrains\\.kotlin\\.android)[^0-9]*([0-9]+(?:\\.[0-9]+){1,2})")
    private val ndkVersionPattern = Regex("ndkVersion[^0-9]*(\\d+(?:\\.\\d+)+)")
    private val cmakeVersionPattern = Regex("cmake[^\\n]*version[^0-9]*(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE)

    fun analyze(projectDir: File, offline: Boolean = false): ProjectCompatibilityReport {
        val root = projectDir.canonicalFile
        val files = root.walkTopDown()
            .filter { it.isFile && it.length() <= 512 * 1024L }
            .filter { it.extension in setOf("gradle", "kts", "yaml", "properties", "toml") }
            .take(80)
            .toList()
        val names = files.map { it.relativeToOrNull(root)?.path ?: it.name }
        val allText = files.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        val type = when {
            File(root, "pubspec.yaml").isFile -> ProjectType.FLUTTER
            File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile ||
                File(root, "build.gradle").isFile || File(root, "build.gradle.kts").isFile -> ProjectType.ANDROID
            else -> ProjectType.GENERAL
        }
        val findings = mutableListOf<CompatibilityFinding>()

        val compileSdk = compileSdkPattern.find(allText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (compileSdk != null) {
            if (compileSdk > 34) findings += CompatibilityFinding(
                id = "compile_sdk",
                severity = CompatibilitySeverity.ERROR,
                message = "Project compileSdk is higher than TaiXu's bundled Android Platform 34",
                current = compileSdk.toString(),
                expected = "<= 34",
                remediation = "Generate a minimal downgrade plan with mobile_project_align or install a matching ARM64 platform",
            ) else findings += CompatibilityFinding(
                id = "compile_sdk",
                severity = CompatibilitySeverity.INFO,
                message = "Project compileSdk can use the bundled platform",
                current = compileSdk.toString(),
                expected = "34",
            )
        }

        val wrapper = listOf(File(root, "gradle/wrapper/gradle-wrapper.properties"), File(root, "android/gradle/wrapper/gradle-wrapper.properties"))
            .firstOrNull { it.isFile }
        val wrapperVersion = wrapper?.let { gradleVersionPattern.find(runCatching { it.readText() }.getOrDefault(""))?.groupValues?.getOrNull(1) }
        if (wrapperVersion != null && wrapperVersion != "8.14.2") findings += CompatibilityFinding(
            id = "gradle_wrapper",
            severity = CompatibilitySeverity.WARNING,
            message = "Project Gradle Wrapper differs from TaiXu's pinned version",
            current = wrapperVersion,
            expected = "8.14.2",
            remediation = "Prefer TaiXu's local Gradle. Generate an alignment plan before modifying the project.",
        )

        agpPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "agp",
                severity = CompatibilitySeverity.INFO,
                message = "Android Gradle Plugin version detected",
                current = version,
                remediation = "If the build fails, let the alignment skill determine compatibility",
            )
        }
        kotlinPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "kotlin",
                severity = CompatibilitySeverity.INFO,
                message = "Kotlin version detected",
                current = version,
                remediation = "Do not rewrite automatically; align only after user confirmation",
            )
        }

        ndkVersionPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "ndk_version",
                severity = CompatibilitySeverity.INFO,
                message = "Project explicitly declares an NDK version",
                current = version,
                remediation = "Compare against source.properties for the NDK selected in Workshop during the build; do not modify the project automatically",
            )
        }
        cmakeVersionPattern.find(allText)?.groupValues?.getOrNull(1)?.let { version ->
            findings += CompatibilityFinding(
                id = "cmake_version",
                severity = CompatibilitySeverity.INFO,
                message = "Project explicitly declares a CMake version",
                current = version,
                remediation = "Compare against the CMake version selected in Workshop during the build; do not modify the project automatically",
            )
        }

        if (Regex("abiFilters[^\\n]*(x86_64|x86|armeabi-v7a)", RegexOption.IGNORE_CASE).containsMatchIn(allText)) {
            findings += CompatibilityFinding(
                id = "x86_abi",
                severity = CompatibilitySeverity.ERROR,
                message = "Project explicitly declares a non-ARM64 ABI",
                expected = "arm64-v8a only",
                remediation = "Set abiFilters to arm64-v8a only; the build entry point automatically cleans generated lib/<abi> directories",
            )
        }

        val cacheHints = if (type == ProjectType.FLUTTER) {
            listOf(File(System.getenv("PUB_CACHE") ?: "", "hosted"))
        } else {
            listOf(File(System.getenv("GRADLE_USER_HOME") ?: File(System.getProperty("user.home"), ".gradle").path, "caches/modules-2/files-2.1"))
        }
        if (offline && cacheHints.none { it.isDirectory }) findings += CompatibilityFinding(
            id = "offline_cache",
            severity = CompatibilitySeverity.ERROR,
            message = "Offline mode is enabled, but the local project dependency cache is missing",
            remediation = "Resolve dependencies online once, or place the project dependency cache in TaiXu's offline cache directory",
        )

        if (findings.isEmpty()) findings += CompatibilityFinding(
            id = "no_constraints",
            severity = CompatibilitySeverity.INFO,
            message = "No explicit constraints beyond the current ARM64 toolchain were found",
        )
        return ProjectCompatibilityReport(root.absolutePath, type, names, findings)
    }
}
