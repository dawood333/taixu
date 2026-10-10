package top.wkbin.taixu.runtime

import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import top.wkbin.taixu.runtime.shell.ShellExecutor
import java.io.File

class RuntimeHealthChecker(
    private val pathManager: RuntimePathManager,
    private val prootCommandBuilder: ProotCommandBuilder,
    private val shellExecutor: ShellExecutor,
) {
    suspend fun check(): RuntimeHealth {
        if (!pathManager.isProotInstalled()) {
            return RuntimeHealth(
                status = RuntimeHealthStatus.UNHEALTHY,
                detail = "PRoot runtime is incomplete: the ARM64 tracee loader in the APK is missing or unreadable",
            )
        }
        if (!pathManager.isRootfsInstalled()) {
            return RuntimeHealth(
                status = RuntimeHealthStatus.UNHEALTHY,
                detail = "Linux RootFS validation failed: Bash, /bin/sh, or its ARM64 ELF interpreter is incomplete",
            )
        }

        val healthMarker = ".taixu-health"
        // 三条 PRoot 命令合并为单次探针：每次冷启动都要完整拉起 proot 进程，
        // 串行 3 次是启动路径上的主要延迟之一。拆分符保证输出仍可精确归位。
        val probeSplit = "__TAIXU_HEALTH_SPLIT__"
        val probeResult = runCatching {
            runCommand(
                ShellCommand(
                    "cat /etc/os-release; echo $probeSplit; uname -m; echo $probeSplit; " +
                        "mkdir -p /workspace && echo taixu-health-ok > /workspace/$healthMarker",
                ),
            )
        }.getOrElse { failedCommandResult(it) }
        val probeParts = probeResult.stdout.split(probeSplit)
        val osRelease = probeParts.getOrNull(0)?.trim().orEmpty()
        val architecture = probeParts.getOrNull(1)?.trim().orEmpty()
        val workspaceFile = File(pathManager.workspaceDir, healthMarker)
        val workspaceWritable = workspaceFile.isFile &&
            workspaceFile.readText().contains("taixu-health-ok")

        val healthy = osRelease.isNotBlank() &&
            architecture.isNotBlank() &&
            workspaceWritable

        return RuntimeHealth(
            status = if (healthy) RuntimeHealthStatus.HEALTHY else RuntimeHealthStatus.UNHEALTHY,
            osRelease = osRelease.ifBlank { null },
            architecture = architecture.ifBlank { null },
            workspaceWritable = workspaceWritable,
            detail = if (healthy) null else buildFailureDetail(probeResult, workspaceWritable),
        )
    }

    private fun buildFailureDetail(
        probe: CommandResult,
        workspaceWritable: Boolean,
    ): String {
        val details = buildList {
            if (probe.isBlankOrFailed()) add(probe.describeFailure("health-probe"))
            if (!workspaceWritable) add("workspace marker missing or unreadable")
        }
        return details.ifEmpty { listOf("Health probe returned empty output") }.joinToString("; ")
    }

    private fun CommandResult.isBlankOrFailed(): Boolean =
        !isSuccess || stdout.isBlank()

    private fun CommandResult.describeFailure(
        label: String,
    ): String {
        val output = (stderr.ifBlank { stdout }).trim().replace(Regex("\\s+"), " ")
        if (output.contains("execve(") && output.contains("No such file or directory")) {
            return "$label exit=$exitCode: the PRoot tracee loader or guest ELF interpreter failed to start"
        }
        if (output.contains("the loader was not found", ignoreCase = true)) {
            return "$label exit=$exitCode: the external PRoot loader is missing or not executable"
        }
        val detail = output.take(240).ifBlank { "no diagnostic output" }
        return "$label exit=$exitCode: $detail"
    }

    private suspend fun runCommand(command: ShellCommand) = shellExecutor.execute(
        command = prootCommandBuilder.build(
            prootBinary = pathManager.activeProotFile(),
            rootfsDir = pathManager.rootfsDir,
            workspaceDir = pathManager.workspaceDir,
            tmpDir = pathManager.tmpDir,
            attachmentsDir = pathManager.attachmentsDir,
            command = command,
        ),
        timeoutMs = command.timeoutMs,
    )

    private fun failedCommandResult(throwable: Throwable) =
        CommandResult(
            exitCode = 126,
            stdout = "",
            stderr = throwable.message ?: throwable.javaClass.simpleName,
            durationMs = 0L,
        )
}
