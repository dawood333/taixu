package top.wkbin.taixu.runtime.doctor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import top.wkbin.taixu.core.model.BuiltinPluginBundles
import top.wkbin.taixu.core.model.DoctorCategory
import top.wkbin.taixu.core.model.DoctorItem
import top.wkbin.taixu.core.model.DoctorReport
import top.wkbin.taixu.core.model.DoctorStatus
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EnvironmentDoctor(
    private val context: Context? = null,
    private val linuxRuntime: LinuxRuntime,
) {
    suspend fun check(): DoctorReport = withContext(Dispatchers.IO) {
        // 宿主侧权限检查：不依赖沙箱状态，两种路径都要展示
        val allFilesAccessItem = checkAllFilesAccess()

        val state = linuxRuntime.state.value
        if (state !is RuntimeState.Ready) {
            val unreadyItem = DoctorItem(
                id = "sandbox_unready",
                category = DoctorCategory.SANDBOX,
                title = "PRoot sandbox status",
                status = DoctorStatus.ERROR,
                summary = if (state is RuntimeState.Error) "Sandbox error: ${state.throwable.message}" else "Sandbox not initialized",
                detail = "Initialize and start the Linux sandbox from the dashboard first",
                fixable = false,
            )
            return@withContext DoctorReport(
                items = listOf(unreadyItem, allFilesAccessItem),
                timestamp = System.currentTimeMillis(),
                overallStatus = DoctorStatus.ERROR,
                healthyCount = itemsHealthy(listOf(unreadyItem, allFilesAccessItem)),
                warningCount = itemsWarning(listOf(unreadyItem, allFilesAccessItem)),
                errorCount = 1,
            )
        }

        val items = mutableListOf<DoctorItem>()

        // 1. 沙箱与存储检查
        items.add(checkSandboxStorage())
        items.add(allFilesAccessItem)

        // 2. DNS 与网络连通性检查
        items.add(checkDnsAndNetwork())

        // 3. CA 根证书检查
        items.add(checkCaCertificates())

        // 4. APT 软件源与镜像加速检查
        items.add(checkAptMirrors())

        // 5. 基础开发工具链检查 (git/curl/tar/xz)
        items.add(checkBaseDevTools())

        // 6. Node.js Runtime检查
        items.add(checkNodeRuntime())

        // 7. Android Development Environment检查（可选插件，未安装时引导获取离线/在线插件包）
        items.add(checkAndroidEnvironment())

        val healthyCount = items.count { it.status == DoctorStatus.HEALTHY }
        val warningCount = items.count { it.status == DoctorStatus.WARNING }
        val errorCount = items.count { it.status == DoctorStatus.ERROR }
        val unknownCount = items.count { it.status == DoctorStatus.UNKNOWN }

        val overallStatus = when {
            errorCount > 0 -> DoctorStatus.ERROR
            warningCount > 0 -> DoctorStatus.WARNING
            unknownCount > 0 -> DoctorStatus.UNKNOWN
            else -> DoctorStatus.HEALTHY
        }

        DoctorReport(
            items = items,
            timestamp = System.currentTimeMillis(),
            overallStatus = overallStatus,
            healthyCount = healthyCount,
            warningCount = warningCount,
            errorCount = errorCount,
            unknownCount = unknownCount,
        )
    }

    /**
     * 沙箱探测统一入口：失败后以双倍超时重试一次。
     * 两次都拿不到结果（异常/超时）返回 null —— 探测不可达 ≠ 配置异常，
     * 调用方应如实给出 UNKNOWN（灰牌「Sandbox is busy; status cannot be confirmed yet」），
     * 绝不把超时误报成 WARNING/ERROR 配置异常。
     */
    private suspend fun probe(commandLine: String, timeoutMs: Long): CommandResult? {
        val first = runCatching {
            linuxRuntime.execute(ShellCommand(commandLine, timeoutMs = timeoutMs))
        }.getOrNull()
        if (first != null) return first
        return runCatching {
            linuxRuntime.execute(ShellCommand(commandLine, timeoutMs = timeoutMs * 2))
        }.getOrNull()
    }

    /** 「Sandbox is busy; status cannot be confirmed yet」灰牌条目：不计入待修复项。 */
    private fun unknownItem(
        id: String,
        category: DoctorCategory,
        title: String,
        detail: String? = null,
    ) = DoctorItem(
        id = id,
        category = category,
        title = title,
        status = DoctorStatus.UNKNOWN,
        summary = "Sandbox is busy; status cannot be confirmed yet",
        detail = detail ?: "The sandbox timed out while handling another task. Run diagnostics again later.",
        fixable = false,
    )

    private fun checkAllFilesAccess(): DoctorItem {
        val granted = if (context == null) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return if (granted) {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "All files access permission",
                status = DoctorStatus.HEALTHY,
                summary = "“All files access” is granted; the file browser can fully access shared storage",
            )
        } else {
            DoctorItem(
                id = "host_all_files_access",
                category = DoctorCategory.SANDBOX,
                title = "All files access permission",
                status = DoctorStatus.WARNING,
                summary = "“All files access” permission is not granted",
                detail = "Android filters files belonging to other apps in shared storage, so the file browser's /sdcard view may show folders but not files. Tap Go to authorization on the right to open system settings.",
                fixable = true,
            )
        }
    }

    private fun itemsHealthy(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.HEALTHY }

    private fun itemsWarning(items: List<DoctorItem>): Int = items.count { it.status == DoctorStatus.WARNING }

    private suspend fun checkSandboxStorage(): DoctorItem {
        val res = probe(
            commandLine = "mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe",
            timeoutMs = 5000L,
        )

        return when {
            res == null -> unknownItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox & Workspace",
                detail = "Read/write probe timed out; the sandbox may be busy (for example, building or installing)",
            )
            res.isSuccess -> DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox & Workspace",
                status = DoctorStatus.HEALTHY,
                summary = "Sandbox is healthy; /workspace and /tmp are writable",
            )
            else -> DoctorItem(
                id = "sandbox_storage",
                category = DoctorCategory.SANDBOX,
                title = "PRoot Sandbox & Workspace",
                status = DoctorStatus.ERROR,
                summary = "Workspace read/write or permission test failed",
                detail = res.stderr.ifBlank { res.stdout }.ifBlank { "Probe command failed" },
            )
        }
    }

    private suspend fun checkDnsAndNetwork(): DoctorItem {
        val resolvCheck = probe("cat /etc/resolv.conf 2>/dev/null", timeoutMs = 3000L)

        // 探测不可达（沙箱正忙/超时）：不确定是否真的缺 DNS，如实给灰牌
        if (resolvCheck == null) {
            return unknownItem(
                id = "network_dns",
                category = DoctorCategory.NETWORK_SSL,
                title = "DNS Resolution",
            )
        }

        val resolvContent = resolvCheck.stdout
        val hasNameserver = resolvContent.contains("nameserver", ignoreCase = true)

        if (!hasNameserver) {
            return DoctorItem(
                id = "network_dns",
                category = DoctorCategory.NETWORK_SSL,
                title = "DNS Resolution",
                status = DoctorStatus.WARNING,
                summary = "No valid DNS resolver is configured",
                detail = "/etc/resolv.conf is empty or missing, which may prevent resolving software download domains",
            )
        }

        return DoctorItem(
            id = "network_dns",
            category = DoctorCategory.NETWORK_SSL,
            title = "DNS Resolution",
            status = DoctorStatus.HEALTHY,
            summary = "DNS resolution is configured correctly",
            detail = resolvContent.lineSequence().filter { it.startsWith("nameserver") }.take(2).joinToString(", "),
        )
    }

    private suspend fun checkCaCertificates(): DoctorItem {
        val certCheck = probe(
            commandLine = "test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs",
            timeoutMs = 3000L,
        )

        return when {
            // 探测不可达：不把「没探测到」当成「证书缺失」
            certCheck == null -> unknownItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL Root Certificates (CA)",
            )
            certCheck.isSuccess -> DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL Root Certificates (CA)",
                status = DoctorStatus.HEALTHY,
                summary = "CA root certificates are ready for HTTPS dependency downloads",
            )
            else -> DoctorItem(
                id = "ca_certificates",
                category = DoctorCategory.NETWORK_SSL,
                title = "SSL Root Certificates (CA)",
                status = DoctorStatus.WARNING,
                summary = "System CA root certificates are missing",
                detail = "SSL verification errors may occur when downloading HTTPS resources or cloning Git repositories",
            )
        }
    }

    private suspend fun checkAptMirrors(): DoctorItem {
        val sourcesCheck = probe(
            commandLine = "cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true",
            timeoutMs = 4000L,
        )

        // 探测不可达：不能因超时误报「官方默认源」（沙箱正忙时 cat 拿不到输出）
        if (sourcesCheck == null) {
            return unknownItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
            )
        }

        val content = sourcesCheck.stdout
        // 命令带 || true，stdout 为空说明源配置整体不可读（而非官方源）——无法判定，如实灰牌
        if (content.isBlank()) {
            return unknownItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                detail = "No APT source configuration was read, so mirror status cannot be determined. The sandbox may be busy or the source list may be invalid; run diagnostics again later.",
            )
        }

        val hasInvalidUbuntuMirror = (content.contains("/ubuntu ") || content.contains("/ubuntu/")) &&
            !content.contains("ubuntu-ports")

        if (hasInvalidUbuntuMirror) {
            return DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.WARNING,
                summary = "APT source configuration is invalid (Ubuntu ARM64 requires the ubuntu-ports source)",
                detail = "An x86 mirror path was detected on ARM64, which can cause package 404 errors. Tap One-tap repair to fix it automatically.",
            )
        }

        val hasDomesticMirror = content.contains("tsinghua.edu.cn", ignoreCase = true) ||
            content.contains("aliyun.com", ignoreCase = true) ||
            content.contains("ustc.edu.cn", ignoreCase = true) ||
            content.contains("tencent.com", ignoreCase = true) ||
            content.contains("163.com", ignoreCase = true)

        return if (hasDomesticMirror) {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.HEALTHY,
                summary = "Regional mirrors are configured (Tsinghua, Alibaba, USTC)",
            )
        } else {
            DoctorItem(
                id = "apt_mirrors",
                category = DoctorCategory.PACKAGE_MANAGER,
                title = "APT Package Sources",
                status = DoctorStatus.WARNING,
                summary = "Official default sources are in use; downloads in some regions may be slow or time out",
                detail = "Switch to a regional mirror for faster, more reliable dependency downloads",
            )
        }
    }

    private suspend fun checkBaseDevTools(): DoctorItem {
        val toolsCheck = probe(
            commandLine = "for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done",
            timeoutMs = 4000L,
        )

        // 探测不可达：不把「没探测到」当成「工具缺失」
        if (toolsCheck == null) {
            return unknownItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Core Toolchain",
            )
        }

        val missingTools = toolsCheck.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }

        return if (missingTools.isEmpty()) {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Core Toolchain",
                status = DoctorStatus.HEALTHY,
                summary = "Common tools such as Git, curl, tar, xz, and file are ready",
            )
        } else {
            DoctorItem(
                id = "base_devtools",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Core Toolchain",
                status = DoctorStatus.WARNING,
                summary = "Missing common tools: ${missingTools.joinToString(", ")}",
                detail = "Some installers and plugins require these tools to extract archives and fetch source code",
            )
        }
    }

    /**
     * 复用插件中心 android-core 组件的同一探针命令，保证体检与插件安装的状态判定口径一致。
     */
    private suspend fun checkAndroidEnvironment(): DoctorItem {
        val checkCommand = BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand

        val installed: Boolean?
        if (checkCommand.isNullOrBlank()) {
            installed = null
        } else {
            val res = probe(
                commandLine = checkCommand,
                timeoutMs = 8000L,
            )
            // 探测不可达时置 null：不把「没探测到」当成「环境未安装」
            installed = res?.isSuccess
        }

        val notInstalledItem = DoctorItem(
            id = "android_environment",
            category = DoctorCategory.DEV_RUNTIMES,
            title = "Android Development Environment",
            status = DoctorStatus.WARNING,
            summary = "Android, Flutter, and decompilation tools are not installed",
            detail = "Download the complete offline plugin bundle from the official QQ group (includes Android, Flutter, and decompilation toolchains), or install the tools online from the Tool Center.",
            fixable = false,
        )

        return when {
            // Bundle 数据未定义探针命令：按未安装引导（与历史行为一致）
            checkCommand.isNullOrBlank() -> notInstalledItem
            installed == true -> DoctorItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android Development Environment",
                status = DoctorStatus.HEALTHY,
                summary = "JDK 17, Android SDK, Gradle, and NDK are ready for building and decompiling APKs",
            )
            // 探针执行了但沙箱不可达：灰牌，不误报「未安装」
            installed == null -> unknownItem(
                id = "android_environment",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Android Development Environment",
                detail = "Android environment probe timed out; the sandbox may be busy with a build. Run diagnostics again later.",
            )
            else -> notInstalledItem
        }
    }

    private suspend fun checkNodeRuntime(): DoctorItem {
        val nodeCheck = probe(
            commandLine = "node --version 2>/dev/null || /opt/taixu/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null",
            timeoutMs = 4000L,
        )

        // 探测不可达：不把「没探测到」当成「Node 未安装」
        if (nodeCheck == null) {
            return unknownItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
            )
        }

        val rawVersion = nodeCheck.stdout.trim()
        if (rawVersion.isBlank()) {
            return DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.WARNING,
                summary = "未检测到 Node.js Runtime",
                detail = "Agent tools such as OpenClaw and Claude Code require Node.js (v20 or later recommended)",
            )
        }

        val majorVersion = Regex("v?(\\d+)").find(rawVersion)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (majorVersion >= 20) {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.HEALTHY,
                summary = "Node.js $rawVersion (meets the requirements of most AI tools)",
            )
        } else {
            DoctorItem(
                id = "node_runtime",
                category = DoctorCategory.DEV_RUNTIMES,
                title = "Node.js Runtime",
                status = DoctorStatus.WARNING,
                summary = "Node.js $rawVersion is outdated (v20 or later recommended)",
                detail = "Newer AI tools may require more recent JavaScript/V8 features",
            )
        }
    }
}
