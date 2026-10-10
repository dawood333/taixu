package top.wkbin.taixu.runtime

import android.content.Context
import android.os.Process
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.runtime.proot.ProotMountLayout

/** 风险描述删除后果；READONLY 表示需要通过所属业务管理，而非文件批量删除。 */
enum class StorageRiskLevel { SAFE, CAUTION, DANGEROUS, READONLY }

data class StorageEntry(
    val id: String,
    val name: String,
    val detail: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val path: String? = null,
    val subItems: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageCategory(
    val id: String,
    val name: String,
    val description: String = "",
    val bytes: Long,
    val cleanable: Boolean = false,
    val riskLevel: StorageRiskLevel = StorageRiskLevel.READONLY,
    val cleanupHint: String? = null,
    val entries: List<StorageEntry> = emptyList(),
    val reclaimableBytes: Long = 0L,
)

data class StorageUsage(
    val availableBytes: Long = 0L,
    val safeCleanableBytes: Long = 0L,
    val cautionCleanableBytes: Long = 0L,
    val categories: List<StorageCategory> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis(),
    val projectCleanableBytes: Long = 0L,
    val scanWarnings: List<String> = emptyList(),
    val excludedMountPointCount: Int = 0,
) {
    /** 文件逻辑大小，不等同于 Android 设置中的应用磁盘分配量。 */
    val totalManagedBytes: Long get() = categories.sumOf { it.bytes }
}

/**
 * 每个文件按最长路径规则只归属一次。扫描结果同时产生清理计划；执行不解析 UI ID 为路径，
 * 不递归删除目录，也不删除扫描后新增、修改或替换的文件。未知数据始终保留在所属类别。
 */
class StorageManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val runtime: Lazy<LinuxRuntime>,
) {
    private enum class Cleanup { NONE, OLD_LOG, DEPENDENCY, PROJECT_CACHE }
    private data class Rule(
        val root: Path,
        val category: String,
        val name: String,
        val hint: String,
        val cleanup: Cleanup = Cleanup.NONE,
        val risk: StorageRiskLevel = StorageRiskLevel.READONLY,
    ) {
        val id: String get() = "$category:$root"
    }
    private data class Target(val path: Path, val size: Long, val modified: FileTime, val key: Any?)
    private data class Plan(val rule: Rule, val targets: List<Target>)
    private val mutex = Mutex()
    private var plans: Map<String, Plan> = emptyMap()
    private var inspected = false
    private val categoryInfo = linkedMapOf(
        "projects" to ("Projects & Personal Files" to "Projects, personal directories, and project caches; source and exports are managed in Workspace"),
        "environment" to ("Linux & Development Environments" to "View distributions, SDKs, and shared runtimes; uninstall them through environment or tool management"),
        "plugins" to ("Plugins & Model Data" to "Plugin binaries, private data, and models; use the owning tool to reset or uninstall them"),
        "conversations" to ("Sessions & Attachments" to "Session database, attachments, and generated files; check session references before deletion"),
        "cache" to ("Caches & Installation Assets" to "Dependency caches can be cleaned as needed; installation packages, staging data, and rollback backups are retained separately"),
        "app_data" to ("App & Diagnostics" to "Configuration, skills, logs, and other data; expired archived logs are the recommended cleanup target"),
    )

    suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) { mutex.withLock { scan() } }

    private suspend fun scan(): StorageUsage {
        inspected = false
        plans = emptyMap()
        val now = System.currentTimeMillis()
        val coroutine = currentCoroutineContext()
        val rules = buildRules().sortedByDescending { it.root.nameCount }
        val totals = mutableMapOf<Rule, Long>()
        val targets = mutableMapOf<Rule, MutableList<Target>>()
        val warnings = mutableListOf<String>()
        val seenKeys = mutableSetOf<Any>()
        val roots = managedRoots()
        val mountCandidates = children(pathManager.distrosDir).filter { it.isDirectory }
            .flatMap { ProotMountLayout.placeholderCandidates(pathManager.rootfsDir(it.name)) }.toSet()
        val excludedMountPoints = mutableSetOf<Path>()
        fun excludeMountPoint(path: Path): Boolean {
            if (path !in mountCandidates || !safePath(path)) return false
            val stat = runCatching { Os.lstat(path.toString()) }.getOrNull() ?: return false
            val excluded = ProotMountLayout.isRestrictedPlaceholder(
                path, mountCandidates, OsConstants.S_ISDIR(stat.st_mode), stat.st_mode and 0xFFF,
                stat.st_uid, Process.myUid(),
            )
            if (excluded) excludedMountPoints.add(path)
            return excluded
        }
        for (root in roots) {
            if (!safePath(root) || !Files.exists(root, NOFOLLOW_LINKS)) continue
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (excludeMountPoint(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    coroutine.ensureActive()
                    if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                    // 同一 inode 的硬链接只计一次，也不作为可释放文件（仍可能有其他链接）。
                    val key = attrs.fileKey()
                    if (key != null && !seenKeys.add(key)) return FileVisitResult.CONTINUE
                    val rule = rules.firstOrNull { file.startsWith(it.root) } ?: return FileVisitResult.CONTINUE
                    totals[rule] = (totals[rule] ?: 0L) + attrs.size()
                    if (eligible(rule, file, attrs, now) && singleLink(file)) {
                        targets.getOrPut(rule) { mutableListOf() }.add(Target(file, attrs.size(), attrs.lastModifiedTime(), key))
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    if (excludeMountPoint(file)) return FileVisitResult.CONTINUE
                    if (warnings.size < 20) warnings.add(scanFailureDetails(file, exc))
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null && excludeMountPoint(dir)) return FileVisitResult.CONTINUE
                    if (exc != null && warnings.size < 20) warnings.add(scanFailureDetails(dir, exc))
                    return FileVisitResult.CONTINUE
                }
            })
        }
        val entries = totals.map { (rule, bytes) ->
            val reclaimable = targets[rule].orEmpty().sumOf { it.size }
            rule to StorageEntry(
                id = rule.id, name = rule.name, detail = rule.hint, bytes = bytes,
                cleanable = reclaimable > 0, riskLevel = rule.risk, cleanupHint = rule.hint,
                path = rule.root.toString(), reclaimableBytes = reclaimable,
            )
        }
        val categories = categoryInfo.map { (id, info) ->
            val children = entries.filter { it.first.category == id }.map { it.second }.sortedByDescending { it.bytes }
            val cleanable = children.filter { it.cleanable }
            StorageCategory(
                id = id, name = info.first, description = info.second, bytes = children.sumOf { it.bytes },
                cleanable = cleanable.isNotEmpty(),
                riskLevel = cleanable.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: StorageRiskLevel.READONLY,
                cleanupHint = "Only files identified as cleanable during the scan and unchanged since then are processed. Dependencies must be downloaded again and project caches regenerated; stop active environments first.",
                entries = children, reclaimableBytes = children.sumOf { it.reclaimableBytes },
            )
        }
        plans = targets.mapKeys { it.key.id }.mapValues { (id, files) -> Plan(rules.first { it.id == id }, files.toList()) }
        inspected = true
        return StorageUsage(
            availableBytes = availableBytes(),
            categories = categories, scannedAt = now, scanWarnings = warnings,
            excludedMountPointCount = excludedMountPoints.size,
            safeCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.SAFE }.sumOf { it.second.reclaimableBytes },
            cautionCleanableBytes = entries.filter { it.second.riskLevel == StorageRiskLevel.CAUTION }.sumOf { it.second.reclaimableBytes },
            projectCleanableBytes = entries.filter { it.first.cleanup == Cleanup.PROJECT_CACHE }.sumOf { it.second.reclaimableBytes },
        )
    }

    private fun buildRules(): List<Rule> = buildList {
        fun addRule(file: File, category: String, name: String, hint: String, cleanup: Cleanup = Cleanup.NONE,
                    risk: StorageRiskLevel = StorageRiskLevel.READONLY) {
            add(Rule(storagePath(file), category, name, hint, cleanup, risk))
        }
        val preserve = "Managed by the owning feature; files are not deleted in bulk directly"
        addRule(context.filesDir.parentFile!!, "app_data", "App-private data", "Includes preferences, WebView data, backup configuration, and other data; managed by the owning feature")
        addRule(context.filesDir, "app_data", "App configuration and other files", preserve)
        addRule(pathManager.baseDir, "app_data", "Runtime metadata and other files", preserve)
        addRule(context.getDatabasePath("taixu.db").parentFile!!, "conversations", "Session and app databases", "Delete records through session management; retain database and WAL/SHM files")
        runCatching { context.cacheDir }.getOrNull()?.let {
            addRule(it, "cache", "App imports and system caches", "May contain files currently being imported; managed by the owning feature")
        }
        runCatching { context.codeCacheDir }.getOrNull()?.let {
            addRule(it, "app_data", "App code cache", "Managed by Android")
        }
        addRule(pathManager.cacheDir, "cache", "Downloads and offline installation archives", "Retain offline-installation and in-progress download files; delete only after the installer confirms completion")
        addRule(File(context.filesDir, "plugins"), "cache", "Local plugin packages", "Reinstallation and plugin recipes may depend on these packages; manage them in Plugin Management")
        addRule(pathManager.stagingRootfsDir, "cache", "Global installation staging", "Owned by installation transactions; excluded from one-tap cleanup")
        addRule(pathManager.tmpDir, "app_data", "Runtime temporary files", "May be in use by PRoot or other processes; excluded from one-tap cleanup")
        val logHint = "Only archived .log.gz / .log.N files older than 7 days and unchanged since then are cleaned; current logs are retained"
        addRule(pathManager.logsDir, "app_data", "Runtime logs", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
        addRule(pathManager.attachmentsDir, "conversations", "Session attachments and generated files", "May be the only copy; verify in the session or file manager before deleting", risk = StorageRiskLevel.DANGEROUS)
        addRule(File(pathManager.attachmentsDir, "skills"), "app_data", "Custom skills", preserve)
        addRule(pathManager.workspaceDir, "projects", "Other workspace files", "Includes exports and unrecognized projects; manage these in Workspace")
        children(pathManager.workspaceDir).filter { it.isDirectory }.forEach { project ->
            addRule(project, "projects", "Project ${project.name} · files", "Source, dependencies, and artifacts; never auto-delete based only on names such as build/dist/out/target")
            // 只识别有工程标志的专用缓存，不推断自定义构建输出路径。
            val gradle = File(project, "settings.gradle").isFile || File(project, "settings.gradle.kts").isFile ||
                File(project, "build.gradle").isFile || File(project, "build.gradle.kts").isFile
            if (gradle) addRule(File(project, ".gradle"), "projects", "Project ${project.name} · Gradle cache",
                "Clean the project's .gradle cache; it will be regenerated on the next build. Keep build, dist, out, target, and source files", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
            if (File(project, "pubspec.yaml").isFile) addRule(File(project, ".dart_tool"), "projects", "Project ${project.name} · Dart cache",
                "Clean .dart_tool; run pub get and rebuild afterward", Cleanup.PROJECT_CACHE, StorageRiskLevel.CAUTION)
        }
        children(pathManager.distrosDir).filter { it.isDirectory }.forEach { distro ->
            val id = distro.name
            val rfs = pathManager.rootfsDir(id)
            val home = pathManager.homeDir(id)
            val taixu = pathManager.taixuRootDir(id)
            addRule(distro, "environment", "$id · system and other environment files", "Includes the Linux system and manually installed software; size changes with use")
            addRule(home, "projects", "$id · personal directory /root", "Separate persistent home mount containing personal files, configuration, and unrecognized dependencies")
            addRule(File(rfs, "root"), "environment", "$id · original /root inside image", "This directory is covered by a separate home mount; original image data is retained")
            addRule(File(rfs, "home"), "projects", "$id · other user directories", preserve)
            addRule(pathManager.rootfsPreviousDir(id), "cache", "$id · upgrade rollback backup", "Deleting this removes rollback capability; handled by the upgrade transaction", risk = StorageRiskLevel.DANGEROUS)
            addRule(pathManager.stagingRootfsDir(id), "cache", "$id · installation staging", "May be in use by an installation or awaiting recovery; excluded from one-tap cleanup")
            addRule(File(taixu, "imports"), "cache", "$id · plugin installation assets", "May be used by a recipe or installation transaction; excluded from one-tap cleanup")
            addRule(File(taixu, "flutter-cache-arm64"), "cache", "$id · Flutter installation assets", "Handled by installation management after completion")
            addRule(File(rfs, "tmp"), "app_data", "$id · temporary files", "Process runtime data; excluded from one-tap cleanup")
            addRule(File(rfs, "var/tmp"), "app_data", "$id · persistent temporary files", "May need to persist across restarts; excluded from one-tap cleanup")
            addRule(File(rfs, "var/log"), "app_data", "$id · system logs", logHint, Cleanup.OLD_LOG, StorageRiskLevel.SAFE)
            listOf(pathManager.taixuToolsDir(id), pathManager.taixuDataDir(id)).forEach { dir ->
                addRule(dir, "plugins", "$id · ${dir.name}", preserve)
                children(dir).forEach { plugin ->
                    addRule(plugin, "plugins", "$id · ${plugin.name} · ${if (dir.name == "data") "data and models" else "binaries"}",
                        "Uninstall or reset through Plugin Management to keep references and the registry consistent")
                }
            }
            listOf(File(taixu, "toolchains"), pathManager.taixuRuntimesDir(id), File(taixu, "compat"), File(rfs, "opt")).forEach { dir ->
                children(dir).filter { it.name != "taixu" }.forEach { sdk ->
                    addRule(sdk, "environment", "$id · ${sdk.name}", "Development bundle or shared runtime; check plugin and project references before uninstalling")
                }
            }
            listOf(".rustup", ".nvm", ".sdkman", ".pyenv").forEach {
                addRule(File(home, it), "environment", "$id · $it", "User-installed language environment managed by its version manager")
            }
            addRule(File(home, ".gradle/wrapper/dists"), "environment", "$id · Gradle Wrapper distributions",
                "Contains extracted runtime components and installation markers; manage by complete version, not by deleting individual old files")
            addRule(File(home, ".gradle/caches"), "cache", "$id · Gradle transforms and other caches",
                "Some caches contain integrity metadata and are managed by Gradle; downloaded dependencies are listed separately")
            addRule(File(home, ".cache/yarn"), "cache", "$id · Yarn cache",
                "May contain extracted packages and integrity markers; manage through Yarn")
            val dependencyHint = "Only unchanged cache files older than 7 days are deleted. They must be downloaded again, which may affect offline builds. Stop terminal sessions, builds, and services first"
            // 仅归档/下载缓存；不清理 pub 全局激活、npm npx 环境、Go 解压源码或 pnpm store。
            listOf(
                ".gradle/caches/modules-2/files-2.1" to "Gradle downloaded dependencies",
                ".cache/pip" to "Pip download cache",
                ".npm/_cacache" to "NPM download cache",
                ".cargo/registry/cache" to "Cargo download archives",
                "go/pkg/mod/cache/download" to "Go download cache",
            ).forEach { (relative, name) ->
                addRule(File(home, relative), "cache", "$id · $name", dependencyHint, Cleanup.DEPENDENCY, StorageRiskLevel.CAUTION)
            }
            addRule(File(rfs, "var/cache/apt/archives"), "cache", "$id · downloaded APT packages",
                dependencyHint, Cleanup.DEPENDENCY, StorageRiskLevel.CAUTION)
        }
    }

    private fun children(dir: File): List<File> = if (safePath(storagePath(dir))) dir.listFiles().orEmpty().filter {
        !Files.isSymbolicLink(it.toPath())
    } else emptyList()

    /** lstat inspects the entry itself, including dangling links, without following its target. */
    private fun scanFailureDetails(path: Path, error: IOException?): String = buildString {
        append("Could not read completely: ").append(path)
        append("\nError: ").append(error?.javaClass?.simpleName ?: "Unknown read error")
        (error as? FileSystemException)?.reason?.takeIf { it.isNotBlank() }?.let {
            append(" · ").append(it)
        }
        // Mirrors the useful metadata inspection in MTDataFilesProvider, not its SAF export.
        // No chmod: a scan must not alter guest permissions or expose app-private files.
        runCatching {
            val stat = Os.lstat(path.toString())
            append("\nPermissions: ").append(Integer.toOctalString(stat.st_mode and 0xFFF))
            append(" · UID：").append(stat.st_uid).append(" · GID：").append(stat.st_gid)
            append(" · App UID: ").append(Process.myUid())
            if (OsConstants.S_ISLNK(stat.st_mode)) {
                append("\nSymlink target: ").append(Os.readlink(path.toString()))
            } else if (OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) {
                val needed = OsConstants.S_IRUSR or OsConstants.S_IXUSR
                if (stat.st_mode and needed != needed) {
                    append("\nThe directory owner's permissions do not allow reading or traversal; this is different from shared-storage authorization.")
                }
            }
        }.onFailure {
            append("\nFailed to read metadata: ").append(it.javaClass.simpleName)
            it.message?.let { message -> append(" · ").append(message) }
        }
    }

    /** Resolve only the trusted Android app root alias, never an arbitrary child symlink. */
    private fun storagePath(file: File): Path {
        val appRoot = context.filesDir.parentFile!!
        val logicalRoot = appRoot.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        return if (path.startsWith(logicalRoot)) appRoot.canonicalFile.toPath().resolve(logicalRoot.relativize(path)) else path
    }

    private fun managedRoots(): List<Path> {
        val paths = listOfNotNull(context.filesDir.parentFile, context.getDatabasePath("taixu.db").parentFile,
            runCatching { context.cacheDir }.getOrNull(), runCatching { context.codeCacheDir }.getOrNull())
            .map(::storagePath).distinct().sortedBy { it.nameCount }
        return paths.filter { path -> paths.none { it != path && path.startsWith(it) } }
    }

    /** 检查全部祖先，不能仅检查叶子链接；PRoot 内的绝对/相对链接均不遍历。 */
    private fun safePath(path: Path): Boolean {
        var cursor: Path? = path.toAbsolutePath().normalize()
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor)) return false
            cursor = cursor.parent
        }
        return true
    }

    private fun singleLink(path: Path): Boolean = try {
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1
    } catch (_: UnsupportedOperationException) {
        // Windows 单元测试文件系统不提供 unix 属性；Android/Linux 支持 nlink。
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IllegalArgumentException) {
        System.getProperty("os.name").orEmpty().startsWith("Windows")
    } catch (_: IOException) { false }

    private fun eligible(rule: Rule, path: Path, attrs: BasicFileAttributes, now: Long): Boolean {
        if (rule.cleanup == Cleanup.NONE || now - attrs.lastModifiedTime().toMillis() < RETENTION_MS) return false
        val relative = rule.root.relativize(path)
        if (relative.any { it.toString() in setOf("partial", ".git") }) return false
        val name = path.fileName.toString()
        if (name == "lock" || name.endsWith(".lock") || name.endsWith(".part") || name.endsWith(".lck")) return false
        return rule.cleanup != Cleanup.OLD_LOG || name.endsWith(".log.gz") || Regex(".*\\.log\\.[0-9]+(?:\\.gz)?").matches(name)
    }

    suspend fun quickSafeClean(): AppResult<Long> = clean { it.rule.risk == StorageRiskLevel.SAFE }

    /** 历史 API 名保留：只清理已识别项目缓存，绝不猜测 build/dist/out/target 的用途。 */
    suspend fun cleanProjectBuildArtifacts(projectName: String? = null): AppResult<Long> = clean(requireMatch = projectName != null) { plan ->
        plan.rule.cleanup == Cleanup.PROJECT_CACHE && (projectName == null ||
            plan.rule.root.parent == storagePath(pathManager.workspaceDir).resolve(projectName))
    }

    suspend fun clearCategory(categoryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId }.map { }

    suspend fun clearEntry(categoryId: String, entryId: String): AppResult<Unit> =
        clean(requireMatch = true) { it.rule.category == categoryId && it.rule.id == entryId }.map { }

    suspend fun clearCache(): AppResult<Unit> = clearCategory("cache")

    private suspend fun clean(requireMatch: Boolean = false, select: (Plan) -> Boolean): AppResult<Long> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    check(inspected) { "Refresh storage usage and review the cleanup scope first" }
                    val selected = plans.values.filter(select)
                    check(!requireMatch || selected.isNotEmpty()) { "This item cannot be cleaned directly or is no longer valid. Refresh and try again." }
                    var released = 0L
                    var skipped = 0
                    var failed = 0
                    runtime.value.withStorageCleanup {
                        for (plan in selected) for (target in plan.targets) {
                            currentCoroutineContext().ensureActive()
                            try {
                                check(target.path.startsWith(plan.rule.root) && safePath(target.path)) { "Path has changed" }
                                if (!Files.exists(target.path, NOFOLLOW_LINKS)) { skipped++; continue }
                                val attrs = Files.readAttributes(target.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                                if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != target.size ||
                                    attrs.lastModifiedTime() != target.modified || attrs.fileKey() != target.key ||
                                    !singleLink(target.path) || !eligible(plan.rule, target.path, attrs, System.currentTimeMillis())) {
                                    skipped++
                                    continue
                                }
                                if (Files.deleteIfExists(target.path)) released += target.size
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (_: Exception) { failed++ }
                        }
                    }
                    plans = emptyMap()
                    inspected = false
                    // 部分完成不能伪装成成功，保留明确结果供 UI 呈现。
                    if (failed > 0 || skipped > 0) AppResult.Failure(AppError(ErrorCode.IO,
                        "Deleted $released bytes; skipped $skipped changed or missing files; $failed failed. Refresh to view remaining usage"))
                    else AppResult.Success(released)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    AppResult.Failure(AppError(ErrorCode.IO, error.message ?: "Cleanup failed", error))
                }
            }
        }

    fun hasEnoughSpace(requiredBytes: Long): Boolean = availableBytes() >= requiredBytes
    private fun availableBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) { context.filesDir.usableSpace }

    private companion object { const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 }
}
