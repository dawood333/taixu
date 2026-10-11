package top.wkbin.taixu.runtime.build

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.ProjectType
import top.wkbin.taixu.runtime.WorkspaceProject
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.datastore.WorkshopPreferences
import top.wkbin.taixu.core.datastore.WorkshopKeystore
import top.wkbin.taixu.core.database.BuildScriptRepository
import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StepDuration(val step: String, val durationMs: Long)

data class BuildRunProgress(
    val step: String,
    val progress: Float = 0f,
    val isRunning: Boolean = true,
    val isSuccess: Boolean? = null,
    val message: String? = null,
    val apkPath: String? = null,
    val logOutput: String = "",
    val suggestedSuiteId: String? = null,
    val stepDurations: List<StepDuration> = emptyList(),
    val totalDurationMs: Long? = null,
    /** 当前正在下载/解析的 Gradle 或 pub 依赖（若构建工具输出了条目）。 */
    val currentDependency: String? = null,
    /** 已观察到的依赖下载/解析条目数；构建工具未提供总数时 total 为 null。 */
    val dependencyItemsObserved: Int = 0,
    val dependenciesTotal: Int? = null,
    /** 构建工具明确输出百分比时才有值，避免用阶段进度冒充下载字节进度。 */
    val dependencyProgressPercent: Int? = null,
)

private const val MAX_LOG_CHARS = 60_000 // 日志上限60KB，超限丢弃旧行

/** 磁盘上最多保留的历史构建日志文件数，超出按最旧清理。 */
private const val KEEP_BUILD_LOG_FILES = 8

// 强制 PTY 后，flutter/gradle 可能输出 ANSI 转义与 \r 进度条；归一化避免日志出现乱码或整行覆盖。
private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
private val ANSI_OSC_REGEX = Regex("\u001B\\][^\u0007]*\u0007")

private fun sanitizeBuildLog(raw: String): String {
    val s = ANSI_OSC_REGEX.replace(ANSI_ESCAPE_REGEX.replace(raw, ""), "")
    // TTY 行规则会把 \n 转成 \r\n，进度条用 \r 覆盖同一行；统一归一化为换行。
    return s.replace("\r\n", "\n").replace("\r", "\n")
}

private data class DependencyObservation(
    val current: String? = null, val seenItems: Set<String> = emptySet(),
    val total: Int? = null, val percent: Int? = null,
)

/**
 * Gradle 和 Flutter 的下载输出没有统一协议：Gradle 通常打印 URL，pub
 * 通常打印 package 名称。因此只提取人类可读的当前条目和已观察数量，
 * 不伪造一个实际上不存在的“总进度”。
 */
private fun observeDependencyOutput(raw: String, previous: DependencyObservation): DependencyObservation {
    var current = previous.current
    val seenItems = previous.seenItems.toMutableSet()
    var total = previous.total
    var percent = previous.percent
    raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
        val lower = line.lowercase()
        val url = Regex("https?://\\S+").find(line)?.value
        val isDependencyLine = lower.contains("download ") ||
            lower.contains("downloading") ||
            lower.contains("downloaded") ||
            lower.contains("fetching") ||
            lower.contains("resolv") ||
            lower.contains("getting dependencies") ||
            lower.startsWith("got dependencies") ||
            url != null
        if (!isDependencyLine) return@forEach

        // Prefer the URL/artifact name, but remove noisy verbs and byte counts.
        val candidate = url
            ?: line.replace(Regex("(?i)^(downloading|downloaded|fetching)\\s*[:：]?\\s*"), "")
                .replace(Regex("\\s+\\([0-9.,]+\\s*(kb|mb|gb|bytes?)\\)"), "")
                .trim()
        if (candidate.isNotBlank() && !candidate.equals("dependencies", ignoreCase = true)) {
            current = candidate
            val genericDependencyStatus = candidate.lowercase().matches(Regex(".*(?:resolving|getting|got) dependencies[.!]*"))
            if (candidate.length > 3 && !genericDependencyStatus) seenItems += candidate
        }

        // Some wrappers print "12/48" or "12 of 48"; use it when available.
        Regex("(\\d+)\\s*(?:/|of)\\s*(\\d+)").find(line)?.let {
            it.groupValues[1].toIntOrNull()?.let { completed ->
                repeat((completed - seenItems.size).coerceAtLeast(0)) { seenItems += "#observed-${seenItems.size}" }
            }
            total = it.groupValues[2].toIntOrNull() ?: total
        }
        Regex("(?<!\\d)(\\d{1,3})%").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
            percent = it.coerceIn(0, 100)
        }
    }
    return DependencyObservation(current, seenItems, total, percent)
}

/**
 * 工作区项目一键构建并安装运行到手机服务。
 */
class WorkspaceBuildRunner(
    private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val embeddedAdbManager: EmbeddedAdbManager,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
    private val runtimePreferences: RuntimePreferences,
    private val workshopPreferences: WorkshopPreferences,
    private val buildScriptRepository: BuildScriptRepository,
    private val signingManager: WorkshopSigningManager,
    private val logger: AppLogger,
) {
    fun launchPackageInstaller(apkFile: File): Boolean {
        return runCatching {
            check(apkFile.isFile && apkFile.length() > 0L) { "APK file does not exist or is empty" }
            val stagedApk = stageApkForInstall(apkFile)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                stagedApk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri("APK", uri)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun stageApkForInstall(apkFile: File): File {
        val dir = File(context.cacheDir, "workspace-apk-installs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && now - it.lastModified() > 24 * 60 * 60 * 1000L }
            .forEach { it.delete() }
        val staged = File(dir, "${apkFile.nameWithoutExtension}-$now-${apkFile.length()}.apk")
        apkFile.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
        check(staged.isFile && staged.length() == apkFile.length()) { "Temporary APK copy is incomplete" }
        return staged
    }

    fun runProject(
        project: WorkspaceProject,
        buildType: WorkshopBuildType = WorkshopBuildType.DEBUG,
        keystore: WorkshopKeystore? = null,
    ): Flow<BuildRunProgress> = channelFlow {
        // 确保每次构建前，沙箱内部的 Shell 资产脚本永远最新且无 BOM 污染
        runCatching {
            assetSynchronizer.syncAssetsToDistro(linuxRuntime.activeDistroId.value)
        }
        val boundScript = buildScriptRepository.resolvedScript(project.name)
            ?.takeIf { it.projectType == project.projectType.name && !it.isBuiltin }
        val workshopAndroidScript = boundScript?.content
            ?: workshopPreferences.androidScript.first()
        val workshopFlutterScript = boundScript?.content
            ?: workshopPreferences.flutterScript.first()
        val boundFileName = boundScript?.id
            ?.replace(Regex("[^A-Za-z0-9._-]"), "-")
            ?.let { "workshop-managed-$it.sh" }
        val androidScriptPath = workshopAndroidScript.takeIf { it.isNotBlank() }?.let {
            runCatching { assetSynchronizer.syncWorkshopScript(linuxRuntime.activeDistroId.value, boundFileName ?: "workshop-build-android.sh", it) }.getOrNull()
        } ?: "/opt/taixu/scripts/taixu-build.sh"
        val flutterScriptPath = workshopFlutterScript.takeIf { it.isNotBlank() }?.let {
            runCatching { assetSynchronizer.syncWorkshopScript(linuxRuntime.activeDistroId.value, boundFileName ?: "workshop-build-flutter.sh", it) }.getOrNull()
        } ?: "/opt/taixu/scripts/taixu-build.sh"
        val isRelease = buildType == WorkshopBuildType.RELEASE
        val androidTask = if (isRelease) "assembleRelease" else "assembleDebug"
        val flutterTarget = if (isRelease) "apk --release --target-platform android-arm64" else "apk --debug --target-platform android-arm64"

        // Release 构建必须先准备签名：同步 keystore 进沙箱并安装 Gradle 签名策略。
        var signingEnvironment: Map<String, String> = emptyMap()
        if (isRelease) {
            if (keystore == null) {
                send(
                    BuildRunProgress(
                        step = "Signing key missing",
                        isRunning = false,
                        isSuccess = false,
                        message = "Release builds require a signing key. Create or import one in Workshop Settings → Signing Management.",
                        logOutput = "[TaiXu Build] ❌ No signing key selected for Release build\n",
                    )
                )
                return@channelFlow
            }
            val prepared = signingManager.prepareReleaseSigning(keystore)
            val env = prepared.getOrNull()
            if (env == null) {
                send(
                    BuildRunProgress(
                        step = "Signing preparation failed",
                        isRunning = false,
                        isSuccess = false,
                        message = prepared.errorOrNull()?.message ?: "Failed to prepare signing key",
                        logOutput = "[TaiXu Build] ❌ ${prepared.errorOrNull()?.message ?: "Failed to prepare signing key"}\n",
                    )
                )
                return@channelFlow
            }
            signingEnvironment = env
        }
        val workshopEnvironment = buildMap {
            workshopPreferences.androidSdkPath.first().takeIf { it.isNotBlank() }?.let { put("ANDROID_HOME", it); put("ANDROID_SDK_ROOT", it) }
            workshopPreferences.ndkPath.first().takeIf { it.isNotBlank() }?.let { put("ANDROID_NDK_HOME", it); put("TAIXU_NDK_PATH", it) }
            workshopPreferences.flutterSdkPath.first().takeIf { it.isNotBlank() }?.let { put("FLUTTER_HOME", it) }
            workshopPreferences.javaPath.first().takeIf { it.isNotBlank() }?.let { put("JAVA_HOME", it) }
            workshopPreferences.gradlePath.first().takeIf { it.isNotBlank() }?.let { put("GRADLE_HOME", it) }
            workshopPreferences.cmakePath.first().takeIf { it.isNotBlank() }?.let { put("TAIXU_CMAKE_HOME", it) }
            workshopPreferences.ninjaPath.first().takeIf { it.isNotBlank() }?.let { put("TAIXU_NINJA_HOME", it) }
            workshopPreferences.aapt2Path.first().takeIf { it.isNotBlank() }?.let { put("TAIXU_AAPT2_PATH", it) }
            workshopPreferences.gradleUserHome.first().takeIf { it.isNotBlank() }?.let { put("GRADLE_USER_HOME", it) }
            workshopPreferences.pubCache.first().takeIf { it.isNotBlank() }?.let { put("PUB_CACHE", it) }
            workshopPreferences.toolDir.first().takeIf { it.isNotBlank() }?.let { put("TAIXU_TOOL_DIR", it) }
            putAll(signingEnvironment)
        }

        // channelFlow 的 Channel 保证多线程 send 的线程安全：
        // 构建输出回调运行在 ProcessShellExecutor 的 stdout/stderr 读取协程中，
        // 必须用 trySend 跨线程投递进度，禁止在回调里直接 emit。
        val progressChannel = this

        // 日志缓冲机制：批量flush + 旧日志丢弃，避免海量日志导致 Compose 卡顿。
        // ⚠️ 线程安全：ProcessShellExecutor 的 stdout/stderr 两个读取协程会并发调用
        // onOutput，心跳协程也在并发 flush——StringBuilder/ArrayList 非线程安全，
        // 并发修改抛出的异常会杀死读协程，进而让整棵构建进程树因管道背压挂死。
        // 所有日志状态必须在 logLock 内访问。
        val logs = StringBuilder()
        val logBuffer = mutableListOf<String>()
        var lastLogFlush = System.currentTimeMillis()
        val buildStartedAt = System.currentTimeMillis()
        val logLock = Any()
        // 最近一次收到进程输出的时间：用于心跳里报告“静默时长”，
        // 让用户能区分「JVM 冷启动/依赖静默下载」和「进程真的挂死」。
        var lastOutputAt = buildStartedAt

        // 持久化构建日志：进程输出在 flush 时同步落盘，构建卡死/失败/被取消后
        // 仍有完整日志可查（UI 内存日志会随对话框关闭丢失）。
        // 写到项目目录的 .taixu/logs/ 下：工作区文件浏览器与沙箱终端都能直接查看，
        // 沙箱内路径为 <linuxPath>/.taixu/logs/<文件名>。
        val buildLogDir = File(project.path, ".taixu/logs").apply { mkdirs() }
        buildLogDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
            ?.dropLast(KEEP_BUILD_LOG_FILES)?.forEach { runCatching { it.delete() } }
        val buildLogFile = File(buildLogDir, "build-$buildStartedAt.log")
        val buildLogWriter = BufferedWriter(FileWriter(buildLogFile))

        fun flushLogBuffer() {
            synchronized(logLock) {
                if (logBuffer.isEmpty()) return
                // 先落盘再进内存缓冲：即使进程随后被强杀，磁盘日志也是完整的。
                runCatching {
                    buildLogWriter.append(logBuffer.joinToString("\n")).append('\n')
                    buildLogWriter.flush()
                }
                for (line in logBuffer) logs.appendLine(line)
                logBuffer.clear()
                // 超限丢弃头部旧日志，只保留最近内容
                if (logs.length > MAX_LOG_CHARS) {
                    val excess = logs.length - MAX_LOG_CHARS
                    val cutIdx = logs.indexOf("\n", excess).let { if (it == -1) excess else it + 1 }
                    logs.delete(0, cutIdx)
                    logs.insert(0, "[...earlier log output discarded...]\n")
                }
            }
        }
        fun log(msg: String) {
            synchronized(logLock) {
                logBuffer.add(sanitizeBuildLog(msg))
                val now = System.currentTimeMillis()
                // 缓冲区满 或 超过 400ms 未刷：批量写入并丢弃超限旧日志
                if (logBuffer.size >= 60 || now - lastLogFlush > 400) {
                    lastLogFlush = now
                    flushLogBuffer() // synchronized 可重入，直接内部调用
                }
            }
        }
        /** 线程安全快照：先冲刷缓冲再取内存日志全文，供 UI 展示。 */
        fun snapshotLogs(): String {
            synchronized(logLock) {
                flushLogBuffer()
                return logs.toString()
            }
        }

        // 心跳：Gradle/JVM/Flutter 在管道(非TTY)下可能长时间无输出（全缓冲或依赖静默下载），
        // 周期性向 UI 推送“仍在构建中”进度，避免用户误以为卡死直到超时。
        var heartbeatStep = "Building, please wait..."
        var heartbeatProgress = 0.35f
        var dependencyObservation = DependencyObservation()
        // ⚠️ 必须持有心跳 Job 并在 finally 里 cancel：channelFlow 要等所有子协程
        // 结束才关闭通道。无限心跳不取消 → flow 永不完成 → 协调器永远收不到
        // “构建结束”，UI 停留在“正在构建”转圈、后台任务标记永不清理；且终态
        // （成功/失败）发出 4 秒后就被心跳覆盖回运行态——成功时看不到安装
        // 提示、失败时看不到错误，都是这个无限子协程造成的。
        val heartbeatJob = launch {
            while (isActive) {
                delay(4_000L)
                flushLogBuffer()
                val silentForSec = (System.currentTimeMillis() - lastOutputAt) / 1000
                // 静默超过 15s 时在步骤文案上明示：不是 UI 卡死，是构建进程暂时没有输出
                val silenceHint = if (silentForSec >= 15) {
                    "(${silentForSec}s without new output: JVM startup/dependency downloads can be quiet; the process is still running)"
                } else ""
                progressChannel.trySend(
                    BuildRunProgress(
                        step = "$heartbeatStep running for ${(System.currentTimeMillis() - buildStartedAt) / 1000}s$silenceHint",
                        progress = heartbeatProgress,
                        isRunning = true,
                        logOutput = snapshotLogs(),
                        currentDependency = dependencyObservation.current,
                        dependencyItemsObserved = dependencyObservation.seenItems.size,
                        dependenciesTotal = dependencyObservation.total,
                        dependencyProgressPercent = dependencyObservation.percent,
                    )
                )
            }
        }

        log("[TaiXu Build Engine] Analyzing project: ${project.name} (${project.projectType.displayName})")
        log("[TaiXu Build] 📄 Full build log: ${project.linuxPath}/.taixu/logs/${buildLogFile.name} (host path: ${buildLogFile.absolutePath})")
        send(BuildRunProgress(step = "Analyzing project environment...", progress = 0.1f, logOutput = snapshotLogs()))

        // finally 兜底：任何 return@channelFlow、异常或用户取消都要落盘并关闭日志文件
        try {
        when (project.projectType) {
            ProjectType.ANDROID -> {
                log("[TaiXu Build] Linux path: ${project.linuxPath}")
                send(BuildRunProgress(step = "Checking the Android build environment...", progress = 0.15f, logOutput = snapshotLogs()))

                // 1. 预检完整 Android 工具链；失败时不启动 Gradle。
                val qemuEnabled = runtimePreferences.qemuCompatibilityEnabled.first()
                val probe = linuxRuntime.execute(ShellCommand(
                    commandLine = BuildEnvironmentPreflight.command(project.linuxPath, ProjectType.ANDROID),
                    timeoutMs = 15_000L,
                    environment = workshopEnvironment,
                ))
                var useQemuBuild = false
                if (!probe.isSuccess && qemuEnabled && shouldRetryWithQemu(probe)) {
                    val qemuProbe = runCatching {
                        linuxRuntime.execute(ShellCommand(
                            commandLine = BuildEnvironmentPreflight.command(project.linuxPath, ProjectType.ANDROID, qemu = true),
                            timeoutMs = 30_000L,
                            useQemuCompatibility = true,
                            environment = workshopEnvironment,
                        ))
                    }.getOrNull()
                    if (qemuProbe?.isSuccess == true) {
                        useQemuBuild = true
                        log("ARM64 toolchain is incompatible; QEMU x86_64 environment preflight passed")
                    }
                }
                if (!probe.isSuccess && !useQemuBuild) {
                    val reason = (probe.stderr + "\n" + probe.stdout).trim().takeLast(800)
                    // 按实际失败项点名，避免把 cmake/ninja/java 等失败误导成"缺 Gradle"
                    val missing = BuildEnvironmentPreflight.describeFailure(reason)
                    log("[TaiXu Build] ⚠️ Android build environment not detected${missing?.let { ": $it" } ?: ""}")
                    log("[TaiXu Build] Preflight reason: $reason")
                    log("[TaiXu Build] 💡 Install the Android & Mobile Development bundle from the Tool Center first.")
                    send(
                        BuildRunProgress(
                            step = "Android build environment missing",
                            isRunning = false,
                            isSuccess = false,
                            message = "Android build preflight failed: ${missing ?: reason.ifBlank { "toolchain incomplete" }}",
                            logOutput = snapshotLogs(),
                            suggestedSuiteId = "android-suite",
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Running Gradle compilation ($androidTask)...")
                send(BuildRunProgress(step = "Running Gradle compilation ($androidTask)...", progress = 0.3f, logOutput = snapshotLogs()))

                // 构建阶段时长追踪
                val buildStartTime = System.currentTimeMillis()
                val stepHistory = mutableListOf<StepDuration>()
                var lastStepTime = buildStartTime
                var previousStep = "Running Gradle build task..."
                fun recordStepDuration(newStep: String) {
                    val now = System.currentTimeMillis()
                    val duration = now - lastStepTime
                    if (duration > 100) stepHistory.add(StepDuration(previousStep, duration))
                    previousStep = newStep
                    lastStepTime = now
                }

                heartbeatStep = "Running Gradle compilation ($androidTask)..."
                heartbeatProgress = 0.35f
                val buildCmd = if (androidScriptPath.endsWith("taixu-build.sh")) {
                    "/bin/sh $androidScriptPath android \"${project.linuxPath}\" $androidTask"
                } else {
                    "/bin/sh $androidScriptPath \"${project.linuxPath}\" $androidTask"
                } + if (useQemuBuild) " --qemu" else ""
                var lastEmitTime = System.currentTimeMillis()
                var currentStep = "Running Gradle build task..."
                var currentProgress = 0.35f

                var outcome = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = buildCmd,
                        forcePty = true,
                        timeoutMs = 1800_000L, // 30 分钟充足超时，适配移动端首次下载海量依赖
                        useQemuCompatibility = useQemuBuild,
                        environment = workshopEnvironment,
                        onOutput = { chunk ->
                            // stdout/stderr 双读协程可能并发进入：共享可变状态统一在 logLock 下串行化
                            synchronized(logLock) {
                            lastOutputAt = System.currentTimeMillis()
                            log(chunk.trimEnd())
                            dependencyObservation = observeDependencyOutput(chunk, dependencyObservation)
                            val lower = chunk.lowercase()
                            when {
                                lower.contains("downloading") || lower.contains("get ") || lower.contains("fetching") -> {
                                    if (currentStep != "Downloading dependencies...") recordStepDuration("Downloading dependencies...")
                                    currentStep = "Downloading dependencies..."
                                    currentProgress = 0.4f
                                }
                                chunk.contains(":compileDebugKotlin") || chunk.contains(":compileReleaseKotlin") -> {
                                    if (currentStep != "Compiling Kotlin / Compose sources...") recordStepDuration("Compiling Kotlin / Compose sources...")
                                    currentStep = "Compiling Kotlin / Compose sources..."
                                    currentProgress = 0.55f
                                }
                                chunk.contains(":compileDebugJavaWithJavac") || chunk.contains(":compileReleaseJavaWithJavac") -> {
                                    if (currentStep != "Compiling Java sources...") recordStepDuration("Compiling Java sources...")
                                    currentStep = "Compiling Java sources..."
                                    currentProgress = 0.65f
                                }
                                chunk.contains(":dexBuilderDebug") || chunk.contains(":mergeExtDexDebug") || chunk.contains(":mergeLibDexDebug") ||
                                    chunk.contains(":dexBuilderRelease") || chunk.contains(":mergeExtDexRelease") || chunk.contains(":mergeLibDexRelease") -> {
                                    if (currentStep != "Converting and optimizing DEX bytecode...") recordStepDuration("Converting and optimizing DEX bytecode...")
                                    currentStep = "Converting and optimizing DEX bytecode..."
                                    currentProgress = 0.75f
                                }
                                chunk.contains(":packageDebug") || chunk.contains(":packageRelease") -> {
                                    if (currentStep != "Packaging APK...") recordStepDuration("Packaging APK...")
                                    currentStep = "Packaging APK..."
                                    currentProgress = 0.85f
                                }
                            }
                            // 限制最快 100ms 刷新一次 UI，避免海量日志高频触发 Compose 重组卡顿
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 100) {
                                lastEmitTime = now
                                progressChannel.trySend(
                                    BuildRunProgress(
                                        step = currentStep,
                                        progress = currentProgress,
                                        logOutput = snapshotLogs(),
                                        currentDependency = dependencyObservation.current,
                                        dependencyItemsObserved = dependencyObservation.seenItems.size,
                                        dependenciesTotal = dependencyObservation.total,
                                        dependencyProgressPercent = dependencyObservation.percent,
                                    )
                                )
                            }
                            } // synchronized(logLock)
                        },
                    ),
                )

                if (!useQemuBuild && !outcome.isSuccess && shouldRetryWithQemu(outcome) && qemuEnabled) {
                    log("[TaiXu Build] ARM64 toolchain cannot run; compatibility mode is enabled. Switching to an isolated x86_64 QEMU build environment...")
                    send(BuildRunProgress(step = "Switching to the QEMU x86_64 compatibility environment...", progress = 0.25f, logOutput = snapshotLogs()))
                    outcome = runCatching {
                        linuxRuntime.execute(ShellCommand(
                            commandLine = if (androidScriptPath.endsWith("taixu-build.sh")) "/bin/sh $androidScriptPath android \"${project.linuxPath}\" $androidTask --qemu" else "/bin/sh $androidScriptPath \"${project.linuxPath}\" $androidTask --qemu",
                            environment = workshopEnvironment,
                            forcePty = true,
                            timeoutMs = 1800_000L,
                            useQemuCompatibility = true,
                            onOutput = { chunk ->
                                synchronized(logLock) {
                                lastOutputAt = System.currentTimeMillis()
                                log(chunk.trimEnd())
                                dependencyObservation = observeDependencyOutput(chunk, dependencyObservation)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 100) {
                                    lastEmitTime = now
                                    progressChannel.trySend(BuildRunProgress(step = "Running the Android build in QEMU x86_64...", progress = 0.5f, logOutput = snapshotLogs(), currentDependency = dependencyObservation.current, dependencyItemsObserved = dependencyObservation.seenItems.size, dependenciesTotal = dependencyObservation.total, dependencyProgressPercent = dependencyObservation.percent))
                                }
                                }
                            },
                        ))
                    }.getOrElse { error ->
                        CommandResult(-1, "", error.message ?: "Failed to start QEMU compatibility environment", 0L)
                    }
                }

                if (!outcome.isSuccess) {
                    // The output callback is buffered; flush it before publishing
                    // failure so the dialog contains the actual Gradle error,
                    // rather than only the final daemon/cache lines.
                    flushLogBuffer()
                    val rawFailureLog = (outcome.stderr + "\n" + outcome.stdout).trim()
                    val diagnostic = rawFailureLog.lineSequence()
                        .filter { line ->
                            val lower = line.lowercase()
                            lower.contains("what went wrong") ||
                                lower.contains("execution failed") ||
                                lower.contains("error:") ||
                                lower.contains("failed with an exception") ||
                                lower.contains("could not")
                        }
                        .take(8)
                        .joinToString("\n")
                    val errLog = (diagnostic.ifBlank { rawFailureLog.takeLast(1600) }).takeLast(1600)
                    log("[TaiXu Build] ❌ Gradle build failed (exit code ${outcome.exitCode})")
                    recordStepDuration("Compilation failed")
                    send(
                        BuildRunProgress(
                            step = "Compilation failed",
                            isRunning = false,
                            isSuccess = false,
                            message = errLog.ifBlank { "Gradle build failed (exit code ${outcome.exitCode})" },
                            logOutput = snapshotLogs(),
                            stepDurations = stepHistory.toList(),
                            totalDurationMs = System.currentTimeMillis() - buildStartTime,
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] ✅ Gradle compilation completed in ${outcome.durationMs}ms")
                log("[TaiXu Build] Searching for APK artifacts...")
                send(BuildRunProgress(step = "Compilation succeeded; searching for APK artifacts...", progress = 0.9f, logOutput = snapshotLogs()))

                // 取最新 mtime 的 APK：目录里可能残留历史构建的旧 APK（目录序
                // firstOrNull 会任意挑），产物校验必须对准本次构建刚写出的文件。
                val apkDir = File(project.path, "app/build/outputs/apk/${if (isRelease) "release" else "debug"}")
                val candidateApk = if (apkDir.isDirectory) {
                    apkDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                        ?.maxByOrNull { it.lastModified() }
                } else null
                val apkFile = candidateApk ?: File(project.path).walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && !it.name.contains("unaligned") }
                    .maxByOrNull { it.lastModified() }

                if (apkFile == null || !apkFile.exists()) {
                    log("[TaiXu Build] ❌ No APK artifact found in the outputs directory")
                    send(
                        BuildRunProgress(
                            step = "Generated APK artifact not found",
                            isRunning = false,
                            isSuccess = false,
                            message = "Build completed, but no APK was found in the outputs directory",
                            logOutput = snapshotLogs(),
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Verifying APK artifact: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")
                val artifactVerification = ApkArtifactVerifier.verify(apkFile)
                log("[TaiXu Build] APK artifact validation: ${artifactVerification.message}")
                if (!artifactVerification.isValid) {
                    send(
                        BuildRunProgress(
                            step = "APK architecture validation failed",
                            isRunning = false,
                            isSuccess = false,
                            message = artifactVerification.message,
                            logOutput = snapshotLogs(),
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Found APK: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")

                // 导出到手机公共存储 Download 目录
                send(BuildRunProgress(step = "Exporting APK to the phone's Downloads directory...", progress = 0.93f, logOutput = snapshotLogs()))
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val totalApkBytes = apkFile.length()
                val targetApk = copyApkAtomically(apkFile, File(downloadDir, "${project.name}.apk")) { copied, _ ->
                    val fraction = if (totalApkBytes > 0) copied.toFloat() / totalApkBytes else 0f
                    progressChannel.trySend(
                        BuildRunProgress(
                            step = "Exporting APK to the phone's Downloads directory... ${copied / 1024 / 1024} MB / ${totalApkBytes / 1024 / 1024} MB",
                            progress = 0.93f + 0.04f * fraction.coerceIn(0f, 1f),
                            logOutput = snapshotLogs(),
                        ),
                    )
                }
                log("[TaiXu Build] APK exported to: ${targetApk.absolutePath}")

                // 多通道安装调度：1. 无线 ADB 直装；2. 调起系统原生 PackageInstaller
                send(BuildRunProgress(step = "Installing on the phone...", progress = 0.97f, logOutput = snapshotLogs()))
                var installNotice = "APK exported to the phone's Downloads/${targetApk.name}"
                val adbInstallResult = runCatching { embeddedAdbManager.installApk(targetApk) }
                if (adbInstallResult.isSuccess) {
                    log("[TaiXu Build] ✅ APK installed directly via built-in ADB!")
                    installNotice = "Installed directly via built-in ADB!"
                    if (project.packageName.isNotBlank()) {
                        log("[TaiXu Build] Launching app: ${project.packageName} ...")
                        embeddedAdbManager.executeShell("monkey -p ${project.packageName} -c android.intent.category.LAUNCHER 1")
                    }
                } else {
                    log("[TaiXu Build] Opening the system package installer (PackageInstaller)...")
                    val installerLaunched = launchPackageInstaller(targetApk)
                    if (installerLaunched) {
                        installNotice = "System package installer opened. Tap Install in the dialog."
                    }
                }

                flushLogBuffer()
                recordStepDuration("Ready to run")
                send(
                    BuildRunProgress(
                        step = "Ready to run",
                        progress = 1.0f,
                        isRunning = false,
                        isSuccess = true,
                        message = installNotice,
                        apkPath = targetApk.absolutePath,
                        logOutput = snapshotLogs(),
                        stepDurations = stepHistory.toList(),
                        totalDurationMs = System.currentTimeMillis() - buildStartTime,
                    )
                )
            }
            ProjectType.FLUTTER -> {
                log("[TaiXu Build] Flutter cross-platform build environment: PUB_HOSTED_URL=https://pub.flutter-io.cn")
                send(BuildRunProgress(step = "Checking the Flutter development environment...", progress = 0.15f, logOutput = snapshotLogs()))

                // 1. 预检 Flutter、Dart 与 Android ARM64 工具链。
                val qemuEnabled = runtimePreferences.qemuCompatibilityEnabled.first()
                val probeFlutter = linuxRuntime.execute(ShellCommand(
                    commandLine = BuildEnvironmentPreflight.command(project.linuxPath, ProjectType.FLUTTER),
                    timeoutMs = 15_000L,
                    environment = workshopEnvironment,
                ))
                var useQemuBuild = false
                if (!probeFlutter.isSuccess && qemuEnabled && shouldRetryWithQemu(probeFlutter)) {
                    val qemuProbe = runCatching {
                        linuxRuntime.execute(ShellCommand(
                            commandLine = BuildEnvironmentPreflight.command(project.linuxPath, ProjectType.FLUTTER, qemu = true),
                            timeoutMs = 30_000L,
                            useQemuCompatibility = true,
                            environment = workshopEnvironment,
                        ))
                    }.getOrNull()
                    if (qemuProbe?.isSuccess == true) {
                        useQemuBuild = true
                        log("Flutter ARM64 toolchain is incompatible; QEMU x86_64 environment preflight passed")
                    }
                }
                if (!probeFlutter.isSuccess && !useQemuBuild) {
                    val reason = (probeFlutter.stderr + "\n" + probeFlutter.stdout).trim().takeLast(800)
                    // 按实际失败项点名，避免把工具链单项缺失误导成整体环境缺失
                    val missing = BuildEnvironmentPreflight.describeFailure(reason)
                    log("[TaiXu Build] ⚠️ Flutter build environment not detected${missing?.let { ": $it" } ?: ""}")
                    log("[TaiXu Build] Preflight reason: $reason")
                    log("[TaiXu Build] 💡 Install the Android & Mobile Development bundle (including Flutter) from the Tool Center first.")
                    send(
                        BuildRunProgress(
                            step = "Flutter build environment missing",
                            isRunning = false,
                            isSuccess = false,
                            message = "Flutter build preflight failed: ${missing ?: reason.ifBlank { "toolchain incomplete" }}",
                            logOutput = snapshotLogs(),
                            suggestedSuiteId = "flutter-suite",
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Building Flutter APK (flutter build $flutterTarget)...")
                send(BuildRunProgress(step = "Running Flutter build (flutter build $flutterTarget)...", progress = 0.3f, logOutput = snapshotLogs()))

                heartbeatStep = "Running Flutter build (flutter build $flutterTarget)..."
                heartbeatProgress = 0.5f
                val buildCmd = if (flutterScriptPath.endsWith("taixu-build.sh")) {
                    "/bin/sh $flutterScriptPath flutter \"${project.linuxPath}\" $flutterTarget"
                } else {
                    "/bin/sh $flutterScriptPath \"${project.linuxPath}\" \"$flutterTarget\""
                } + if (useQemuBuild) " --qemu" else ""
                var lastEmitTime = System.currentTimeMillis()

                var outcome = linuxRuntime.execute(
                    ShellCommand(
                        commandLine = buildCmd,
                        forcePty = true,
                        timeoutMs = 1800_000L,
                        useQemuCompatibility = useQemuBuild,
                        environment = workshopEnvironment,
                        onOutput = { chunk ->
                            synchronized(logLock) {
                            lastOutputAt = System.currentTimeMillis()
                            log(chunk.trimEnd())
                            dependencyObservation = observeDependencyOutput(chunk, dependencyObservation)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 100) {
                                lastEmitTime = now
                                progressChannel.trySend(BuildRunProgress(step = "Running Flutter build...", progress = 0.5f, logOutput = snapshotLogs(), currentDependency = dependencyObservation.current, dependencyItemsObserved = dependencyObservation.seenItems.size, dependenciesTotal = dependencyObservation.total, dependencyProgressPercent = dependencyObservation.percent))
                            }
                            }
                        },
                    ),
                )

                if (!useQemuBuild && !outcome.isSuccess && shouldRetryWithQemu(outcome) && qemuEnabled) {
                    log("[TaiXu Build] Flutter ARM64 toolchain cannot run; switching to an isolated x86_64 QEMU build environment...")
                    send(BuildRunProgress(step = "Switching to the QEMU x86_64 Flutter environment...", progress = 0.25f, logOutput = snapshotLogs()))
                    outcome = runCatching {
                        linuxRuntime.execute(ShellCommand(
                            commandLine = if (flutterScriptPath.endsWith("taixu-build.sh")) "/bin/sh $flutterScriptPath flutter \"${project.linuxPath}\" --qemu" else "/bin/sh $flutterScriptPath \"${project.linuxPath}\" --qemu",
                            environment = workshopEnvironment,
                            forcePty = true,
                            timeoutMs = 1800_000L,
                            useQemuCompatibility = true,
                            onOutput = { chunk ->
                                synchronized(logLock) {
                                lastOutputAt = System.currentTimeMillis()
                                log(chunk.trimEnd())
                                dependencyObservation = observeDependencyOutput(chunk, dependencyObservation)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 100) {
                                    lastEmitTime = now
                                    progressChannel.trySend(BuildRunProgress(step = "Running the Flutter build in QEMU x86_64...", progress = 0.5f, logOutput = snapshotLogs(), currentDependency = dependencyObservation.current, dependencyItemsObserved = dependencyObservation.seenItems.size, dependenciesTotal = dependencyObservation.total, dependencyProgressPercent = dependencyObservation.percent))
                                }
                                }
                            },
                        ))
                    }.getOrElse { error ->
                        CommandResult(-1, "", error.message ?: "Failed to start QEMU compatibility environment", 0L)
                    }
                }

                if (!outcome.isSuccess) {
                    flushLogBuffer()
                    val rawFailureLog = (outcome.stderr + "\n" + outcome.stdout).trim()
                    val diagnostic = rawFailureLog.lineSequence()
                        .filter { line ->
                            val lower = line.lowercase()
                            lower.contains("what went wrong") ||
                                lower.contains("execution failed") ||
                                lower.contains("error:") ||
                                lower.contains("failed with an exception") ||
                                lower.contains("could not")
                        }
                        .take(8)
                        .joinToString("\n")
                    val errLog = (diagnostic.ifBlank { rawFailureLog.takeLast(1600) }).takeLast(1600)
                    log("[TaiXu Build] ❌ Flutter build failed (exit code ${outcome.exitCode})")
                    flushLogBuffer()
                    send(
                        BuildRunProgress(
                            step = "Flutter Compilation failed",
                            isRunning = false,
                            isSuccess = false,
                            message = errLog.ifBlank { "Flutter build failed (exit code ${outcome.exitCode})" },
                            logOutput = snapshotLogs(),
                            totalDurationMs = outcome.durationMs,
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] ✅ Flutter compilation completed in ${outcome.durationMs}ms")
                send(BuildRunProgress(step = "Compilation succeeded; exporting APK...", progress = 0.8f, logOutput = snapshotLogs()))

                // 取最新 mtime 的 APK（理由同 Android 路径）：flutter-apk 目录可能
                // 残留旧构建的胖 APK，目录序 firstOrNull 会任意挑导致误判。
                val apkDir = File(project.path, "build/app/outputs/flutter-apk")
                val candidateApk = if (apkDir.isDirectory) {
                    apkDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                        ?.maxByOrNull { it.lastModified() }
                } else null
                val apkFile = candidateApk ?: File(project.path).walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    .maxByOrNull { it.lastModified() }

                if (apkFile == null || !apkFile.exists()) {
                    log("[TaiXu Build] ❌ No Flutter APK artifact found in the outputs directory")
                    send(
                        BuildRunProgress(
                            step = "Generated Flutter APK artifact not found",
                            isRunning = false,
                            isSuccess = false,
                            message = "Build completed, but no APK was found in the outputs directory",
                            logOutput = snapshotLogs(),
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Verifying Flutter APK artifact: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")
                val artifactVerification = ApkArtifactVerifier.verify(apkFile)
                log("[TaiXu Build] Flutter APK artifact validation: ${artifactVerification.message}")
                if (!artifactVerification.isValid) {
                    send(
                        BuildRunProgress(
                            step = "Flutter APK architecture validation failed",
                            isRunning = false,
                            isSuccess = false,
                            message = artifactVerification.message,
                            logOutput = snapshotLogs(),
                        )
                    )
                    return@channelFlow
                }

                log("[TaiXu Build] Found Flutter APK: ${apkFile.absolutePath}")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val totalApkBytes = apkFile.length()
                val targetApk = copyApkAtomically(apkFile, File(downloadDir, "${project.name}.apk")) { copied, _ ->
                    val fraction = if (totalApkBytes > 0) copied.toFloat() / totalApkBytes else 0f
                    progressChannel.trySend(
                        BuildRunProgress(
                            step = "Exporting APK to the phone's Downloads directory... ${copied / 1024 / 1024} MB / ${totalApkBytes / 1024 / 1024} MB",
                            progress = 0.93f + 0.04f * fraction.coerceIn(0f, 1f),
                            logOutput = snapshotLogs(),
                        ),
                    )
                }
                log("[TaiXu Build] Flutter APK exported to: ${targetApk.absolutePath}")

                send(BuildRunProgress(step = "Installing on the phone...", progress = 0.95f, logOutput = snapshotLogs()))
                var installNotice = "Flutter APK exported to the phone's Downloads/${targetApk.name}"
                val adbInstallResult = runCatching { embeddedAdbManager.installApk(targetApk) }
                if (adbInstallResult.isSuccess) {
                    log("[TaiXu Build] ✅ Flutter app installed directly via built-in ADB!")
                    installNotice = "Flutter app installed directly via built-in ADB!"
                } else {
                    log("[TaiXu Build] Opening the system package installer (PackageInstaller)...")
                    val installerLaunched = launchPackageInstaller(targetApk)
                    if (installerLaunched) {
                        installNotice = "System package installer opened. Tap Install in the dialog."
                    }
                }

                flushLogBuffer()
                send(
                    BuildRunProgress(
                        step = "Ready to run",
                        progress = 1.0f,
                        isRunning = false,
                        isSuccess = true,
                        message = installNotice,
                        apkPath = targetApk.absolutePath,
                        logOutput = snapshotLogs(),
                        totalDurationMs = outcome.durationMs,
                    )
                )
            }
            ProjectType.REVERSE -> {
                log("[TaiXu Build] APK reverse-engineering project; no build is required. Providing jadx/apktool analysis instructions.")
                send(
                    BuildRunProgress(
                        step = "APK reverse-engineering project",
                        isRunning = false,
                        isSuccess = true,
                        message = "No build is required for reverse engineering. Open the project terminal or Agent chat and use jadx/apktool to unpack and decompile the APK (see REVERSE.md in the project).",
                        logOutput = snapshotLogs(),
                    )
                )
            }
            ProjectType.GENERAL -> {
                log("[TaiXu Build] Generic project; no default APK packaging workflow")
                send(
                    BuildRunProgress(
                        step = "Generic project",
                        isRunning = false,
                        isSuccess = true,
                        message = "For a generic project, run custom commands or build scripts in the TaiXu terminal",
                        logOutput = snapshotLogs(),
                    )
                )
            }
        }
        } finally {
            // 先停心跳再收尾：保证终态是通道里最后一条消息（见 heartbeatJob 声明处说明）。
            heartbeatJob.cancel()
            log("[TaiXu Build] 📄 Build finished. Full log saved to: ${project.linuxPath}/.taixu/logs/${buildLogFile.name}")
            runCatching {
                flushLogBuffer()
                buildLogWriter.flush()
                buildLogWriter.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun shouldRetryWithQemu(outcome: CommandResult): Boolean {
        val text = (outcome.stdout + "\n" + outcome.stderr).lowercase()
        // not_elf / 包装脚本回环 = 工具链文件本身被损坏（典型：exec 回环把
        // JDK 启动器覆盖成脚本）。这是中毒信号，不是架构兼容问题——
        // 切 QEMU 只会用另一套工具链掩盖病灶并多烧几分钟，必须原地报错
        // 让用户去插件中心重装套件。
        if (text.contains("not_elf") ||
            text.contains("疑似包装脚本") ||
            text.contains("possible wrapper script") ||
            text.contains("回环软链") ||
            text.contains("symlink loop")
        ) {
            return false
        }
        return outcome.exitCode == 126 ||
            text.contains("exec format") ||
            text.contains("not executable") ||
            text.contains("wrong elf class") ||
            text.contains("taixu_preflight_fail: java_arch") ||
            text.contains("taixu_preflight_fail: aapt2_arch") ||
            text.contains("taixu_preflight_fail: ndk_arch") ||
            text.contains("taixu_preflight_fail: dart_arch") ||
            text.contains("elf 架构不匹配") ||
            text.contains("elf architecture mismatch") ||
            text.contains("不是 arm64 elf") ||
            text.contains("not an arm64 elf") ||
            text.contains("aarch64") && text.contains("架构") ||
            text.contains("主机工具不是可执行") ||
            text.contains("host tool is not an executable")
    }
    /** Write a complete APK into the public Download directory; returns the file actually written. */
    private fun copyApkAtomically(
        source: File,
        target: File,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        check(source.isFile && source.length() > 0L) { "APK source file does not exist or is empty: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        try {
            val totalBytes = source.length()
            var copiedBytes = 0L
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        onProgress(copiedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            check(temporary.length() == source.length()) {
                "APK export is incomplete: ${temporary.length()} / ${source.length()} bytes"
            }
            // 优先覆盖固定名；若旧文件受 scoped storage 保护无法覆盖，则回退到带时间戳的唯一文件名。
            val exported = if (replaceTarget(temporary, target)) target else {
                val unique = uniqueTarget(target)
                check(replaceTarget(temporary, unique)) { "APK export failed: unable to write to the phone's Downloads directory" }
                unique
            }
            check(exported.isFile && exported.length() == source.length()) { "APK export target is invalid" }
            return exported
        } finally {
            temporary.delete()
        }
    }
    /** 用临时文件替换目标；覆盖失败（例如旧文件不可删除）返回 false。 */
    private fun replaceTarget(source: File, target: File): Boolean = try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: Throwable) {
        false
    }
    private fun uniqueTarget(target: File): File {
        val dot = target.name.lastIndexOf('.')
        val base = if (dot > 0) target.name.substring(0, dot) else target.name
        val ext = if (dot > 0) target.name.substring(dot) else ""
        return File(target.parentFile, "$base-${System.currentTimeMillis()}$ext")
    }
}
