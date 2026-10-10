package top.wkbin.taixu.runtime

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.common.shell.ShellQuote
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.core.database.WorkspaceEntity
import top.wkbin.taixu.core.database.BuildScriptRepository
import top.wkbin.taixu.template.ProjectTemplateEngine
import top.wkbin.taixu.template.TemplateProjectType
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class ProjectType {
    ANDROID,
    FLUTTER,
    REVERSE,
    GENERAL;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK reverse engineering"
            GENERAL -> "General"
        }
}

enum class ProjectImportSource {
    LOCAL_ARCHIVE,
    GITHUB,
    TEMPLATE,
}

enum class GitTransport {
    HTTP,
    SSH,
}

data class ProjectArchiveSource(
    val uri: String,
    val fileName: String,
)

enum class ProjectTemplate {
    EMPTY,
    APK_REVERSE,
    GIT_IMPORT;

    val displayName: String
        get() = when (this) {
            EMPTY -> "Empty project"
            APK_REVERSE -> "APK reverse engineering"
            GIT_IMPORT -> "Import from Git"
        }
}

/**
 * APK reverse-engineering template的安装包来源：
 * - [FromInstalledApp]：从本机已安装应用提取安装包（applicationInfo.sourceDir）；
 * - [FromFileUri]：通过系统文件管理器（SAF OpenDocument）选择 .apk 文件。
 */
sealed class ApkImportSource {
    data class FromInstalledApp(
        val packageName: String,
        val appLabel: String,
    ) : ApkImportSource()

    data class FromFileUri(
        val uri: String,
        val fileName: String,
    ) : ApkImportSource()

    val displayName: String
        get() = when (this) {
            is FromInstalledApp -> "$appLabel ($packageName)"
            is FromFileUri -> fileName
        }
}

data class WorkspaceProject(
    val name: String,
    val path: String,
    val linuxPath: String,
    val sizeBytes: Long,
    val ownsDirectory: Boolean = true,
    val projectType: ProjectType = ProjectType.GENERAL,
    val packageName: String = "",
)

enum class WorkspaceStorage { INTERNAL, SHARED }

data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val extension: String = "",
)

/** 工作区：目录在 App 私有挂载点，元数据（路径/创建时间）存 Room。 */
class WorkspaceManager(
    private val context: Context,
    private val pathManager: RuntimePathManager,
    private val workspaceDao: WorkspaceRepository,
    private val fileService: WorkspaceFileService,
    private val linuxRuntime: Lazy<LinuxRuntime>,
    private val projectTemplateEngine: ProjectTemplateEngine,
    private val buildScriptRepository: Lazy<BuildScriptRepository>? = null,
) {
    constructor(
        pathManager: RuntimePathManager,
        workspaceDao: WorkspaceRepository,
        projectTemplateEngine: ProjectTemplateEngine,
    ) : this(
        ContextWrapper(null),
        pathManager,
        workspaceDao,
        WorkspaceFileService(pathManager, workspaceDao),
        lazy<LinuxRuntime> { error("Linux runtime is unavailable in this test constructor") },
        projectTemplateEngine,
        null,
    )
    fun observeProjects(): Flow<List<WorkspaceProject>> = workspaceDao.observeAll().map { entities ->
        val projectPaths = entities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = entities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered.mapNotNull(::projectFromEntity)
    }.flowOn(Dispatchers.IO)

    suspend fun listProjects(): List<WorkspaceProject> = withContext(Dispatchers.IO) {
        pathManager.workspaceDir.mkdirs()
        // 目录为准；缺失的目录从 Room 补录
        val known = workspaceDao.listAll().associateBy { it.name }
        val knownPaths = known.values.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val directories = pathManager.workspaceDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isValidProjectName(it.name) && !File(it, UNLINKED_MARKER).exists() }
        directories.forEach { directory ->
            if (directory.name !in known && directory.canonicalPath !in knownPaths) {
                workspaceDao.upsert(
                    WorkspaceEntity(directory.name, directory.absolutePath, System.currentTimeMillis()),
                )
            }
        }
        // 先收集所有有效实体，再过滤掉作为其他项目父目录的实体
        // （避免嵌套路径创建项目时，父目录被误注册为独立项目）
        val allEntities = workspaceDao.listAll().filter { it.name in known || File(it.path).isDirectory }
        val projectPaths = allEntities.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toSet()
        val filtered = allEntities.filter { entity ->
            val entityCanonical = runCatching { File(entity.path).canonicalPath }.getOrNull() ?: return@filter true
            // 如果存在其他项目的路径以此实体路径为前缀，则此实体是父目录，应过滤掉
            projectPaths.none { otherPath ->
                otherPath != entityCanonical && otherPath.startsWith(entityCanonical + File.separator)
            }
        }
        filtered
            .filter { entity -> File(entity.path).isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull(::projectFromEntity)
    }

    suspend fun createProject(
        name: String,
        storage: WorkspaceStorage = WorkspaceStorage.INTERNAL,
        directoryPath: String = "",
        template: ProjectTemplate = ProjectTemplate.EMPTY,
        packageName: String = "",
        apkSource: ApkImportSource? = null,
        exportApkToDownload: Boolean = false,
        gitUrl: String = "",
        templateVariables: Map<String, String> = emptyMap(),
        templateId: String = "",
        trustTemplateScripts: Boolean = false,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "Name must start with a letter or number and may contain only letters, numbers, dots, underscores, and hyphens" }
            require(safeName != "sdcard") { "sdcard is reserved for shared system storage" }
            check(workspaceDao.findByName(safeName) == null) { "Project already exists: $safeName" }
            val resolvedTemplateId = templateId
            val resolvedManifest = resolvedTemplateId.takeIf(String::isNotBlank)?.let(projectTemplateEngine::inspect)
            resolvedManifest?.variables?.firstOrNull { it.name == "projectName" }?.let { variable ->
                require(!variable.required || safeName.isNotBlank()) { "Project name cannot be empty" }
                if (variable.validationRegex.isNotBlank()) {
                    require(Regex(variable.validationRegex).matches(safeName)) {
                        variable.description.ifBlank { "Project name does not match the selected template's requirements" }
                    }
                }
            }
            pathManager.workspaceDir.mkdirs()
            val base = when (storage) {
                WorkspaceStorage.INTERNAL -> pathManager.workspaceDir
                WorkspaceStorage.SHARED -> SHARED_STORAGE_ROOT
            }
            check(base.isDirectory || base.mkdirs()) { "Linked workspace is unavailable: ${base.absolutePath}" }
            val prefix = if (storage == WorkspaceStorage.INTERNAL) "/workspace/" else "/sdcard/"
            val requested = directoryPath.trim().replace('\\', '/').removePrefix(prefix).trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Linked directory contains an invalid path" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base.canonicalFile, directory) && directory != base.canonicalFile) { "Linked directory is outside the allowed path" }
            val duplicate = workspaceDao.listAll().any {
                it.name != safeName && runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "This directory is already linked to another project" }
            val existed = directory.exists()
            if (template == ProjectTemplate.GIT_IMPORT) {
                require(isValidGitUrl(gitUrl)) { "Git repository URL must use HTTPS, SSH, or git@ format" }
                require(!existed || directory.isDirectory) { "Git import target is not a directory" }
                require(!existed || directory.listFiles().orEmpty().isEmpty()) { "Git import target directory must be empty" }
                if (!existed) require(directory.mkdirs()) { "Unable to create Git import directory" }
            } else {
                check((existed && directory.isDirectory) || (!existed && directory.mkdirs())) { "Unable to create or access linked directory" }
                if (template != ProjectTemplate.EMPTY || templateId.isNotBlank()) {
                    check(!existed || directory.listFiles().orEmpty().isEmpty()) { "Template target directory must be empty" }
                }
            }
            File(directory, UNLINKED_MARKER).delete()

            // 模板初始化处理
            // APK reverse engineering模板：包名无需用户输入，由导入的安装包决定（无则留空）
            val needsPackageName = resolvedManifest?.variables.orEmpty().any {
                it.name == "packageName" || it.name == "packagePath"
            }
            var effectivePackage = ""
            if (needsPackageName) {
                val packageDefault = resolvedManifest?.variables?.firstOrNull { it.name == "packageName" }?.defaultValue.orEmpty()
                val cleanPkg = templateVariables["packageName"].orEmpty().trim()
                    .ifBlank { packageName.trim() }
                    .ifBlank { packageDefault }
                    .ifBlank { "com.example.${safeName.lowercase().filter { it.isLetterOrDigit() }}" }
                require(PACKAGE_NAME.matches(cleanPkg)) { "Package name must be a valid Java/Kotlin package name: $cleanPkg" }
                effectivePackage = cleanPkg
            }
            if (resolvedTemplateId.isNotBlank() && resolvedManifest != null) {
                val suppliedValues = templateVariables.toMutableMap().apply {
                    this["projectName"] = safeName
                    this["projectPath"] = linuxPathFor(directory)
                    putIfAbsent("appName", safeName)
                    if (needsPackageName) {
                        this["packageName"] = effectivePackage
                        this["packagePath"] = effectivePackage.replace('.', '/')
                    } else {
                        remove("packageName")
                        remove("packagePath")
                    }
                }
                val values = projectTemplateEngine.resolvedValues(resolvedManifest, suppliedValues)
                val hasScripts = resolvedManifest.hooks.beforeCreate.isNotBlank() || resolvedManifest.hooks.afterCreate.isNotBlank()
                require(!hasScripts || trustTemplateScripts) { "This template contains setup scripts. Review and explicitly authorize them first." }
                if (resolvedManifest.hooks.beforeCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "before-create",
                        resolvedManifest.hooks.beforeCreate,
                        directory,
                        values,
                    )
                }
                projectTemplateEngine.materialize(
                    resolvedTemplateId,
                    directory,
                    values,
                )
                if (resolvedManifest.hooks.afterCreate.isNotBlank()) {
                    executeTemplateHook(
                        resolvedTemplateId,
                        "after-create",
                        resolvedManifest.hooks.afterCreate,
                        directory,
                        values,
                    )
                }
                projectTemplateEngine.validateMaterialized(resolvedTemplateId, directory, values)
                writeProjectTypeMetadata(
                    directory = directory,
                    type = when (resolvedManifest.projectType) {
                        TemplateProjectType.ANDROID -> ProjectType.ANDROID
                        TemplateProjectType.FLUTTER -> ProjectType.FLUTTER
                        TemplateProjectType.GENERAL -> ProjectType.GENERAL
                    },
                    source = ProjectImportSource.TEMPLATE,
                )
            } else when (template) {
                ProjectTemplate.APK_REVERSE -> {
                    val imported = importApkForReverse(directory, safeName, apkSource)
                    effectivePackage = imported.packageName
                    if (exportApkToDownload) {
                        exportApkToDownload(imported.apkFileName, directory, safeName)
                    }
                }
                ProjectTemplate.GIT_IMPORT -> cloneGitRepository(directory, gitUrl, cleanupOnFailure = !existed)
                ProjectTemplate.EMPTY -> { /* 保持空目录 */ }
            }

            val ownsDirectory = storage == WorkspaceStorage.INTERNAL && !existed
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory),
            )
            val createdEntity = workspaceDao.findByName(safeName)
            if (createdEntity == null) {
                AppResult.Failure(AppError(ErrorCode.IO, "Failed to query project after writing", null))
            } else {
                val created = projectFromEntity(createdEntity)
                if (created == null) {
                    AppResult.Failure(AppError(ErrorCode.IO, "Failed to convert project data", null))
                } else {
                    AppResult.Success(created)
                }
            }
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Failed to create project", throwable))
        }
    }

    /**
     * Imports a ZIP project archive into an internal sandbox directory and records the user-selected
     * project label. Extraction always happens under /workspace and rejects path traversal entries.
     */
    suspend fun importProjectArchive(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        source: ProjectArchiveSource,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.LOCAL_ARCHIVE) { directory, cleanupOnFailure ->
            try {
                extractProjectArchive(source, directory)
            } catch (throwable: Throwable) {
                if (cleanupOnFailure) SafeFileTree.delete(directory)
                throw throwable
            }
        }
    }

    /** Imports a GitHub repository over the explicitly selected HTTP(S) or SSH transport. */
    suspend fun importGithubProject(
        name: String,
        directoryPath: String = "",
        projectType: ProjectType,
        gitUrl: String,
        transport: GitTransport,
        onProgress: ((String) -> Unit)? = null,
    ): AppResult<WorkspaceProject> = withContext(Dispatchers.IO) {
        importProject(name, directoryPath, projectType, ProjectImportSource.GITHUB) { directory, cleanupOnFailure ->
            require(isValidGitUrlForTransport(gitUrl, transport)) {
                when (transport) {
                    GitTransport.HTTP -> "HTTP URL must start with http:// or https://"
                    GitTransport.SSH -> "SSH URL must use ssh:// or git@host:path format"
                }
            }
            cloneGitRepository(directory, gitUrl, cleanupOnFailure, onProgress)
        }
    }

    /** Compresses all regular project files and exports the ZIP into a SAF-selected local directory. */
    suspend fun exportProject(name: String, targetTreeUri: String): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "Invalid project name" }
            val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
            val projectDir = File(entity.path).canonicalFile
            check(projectDir.isDirectory) { "Project directory not found: $name" }

            val treeUri = Uri.parse(targetTreeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val fileName = "$name-${System.currentTimeMillis()}.zip"
            val outputUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                "application/zip",
                fileName,
            ) ?: error("Unable to create the export file in the selected directory")
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("Unable to write the export file")
            output.use { stream -> writeProjectZip(projectDir, stream) }
            AppResult.Success(fileName)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Failed to export project", throwable))
        }
    }

    suspend fun deleteProject(name: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            require(isValidProjectName(name)) { "Invalid project name" }
            val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
            val directory = File(entity.path)
            if (entity.ownsDirectory && directory.exists()) {
                SafeFileTree.delete(directory)
            } else if (directory.isDirectory) {
                File(directory, UNLINKED_MARKER).writeText("unlinkedAt=${System.currentTimeMillis()}\n")
            }
            workspaceDao.delete(name)
            runCatching { buildScriptRepository?.value?.unbind(name) }
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Failed to delete project", throwable))
        }
    }

    suspend fun linuxWorkingDirectory(name: String): String {
        if (name == "sdcard") return "/sdcard"
        require(isValidProjectName(name)) { "Invalid project name" }
        val entity = workspaceDao.findByName(name) ?: error("Project not found: $name")
        check(File(entity.path).isDirectory) { "Linked directory does not exist: $name" }
        return linuxPathFor(File(entity.path))
    }

    /** 会话关联的工作区目录；返回 null 表示不关联。 */
    suspend fun workspaceForName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return runCatching { linuxWorkingDirectory(name) }.getOrNull()
    }

    // ==================== 项目内文件管理 API ====================

    /**
     * 该项目根目录是否位于宿主共享存储（/storage/emulated/0）之下。
     * 共享存储受 Android 11+ "所有文件访问" 权限约束：未授权时系统会过滤
     * 其他应用的文件（典型表现是只列出文件夹），需要引导用户授权。
     */
    suspend fun usesSharedStorage(projectName: String): Boolean = withContext(Dispatchers.IO) {
        if (projectName == "sdcard") return@withContext true
        val entityPath = workspaceDao.findByName(projectName)?.path ?: return@withContext false
        val canonical = runCatching { File(entityPath).canonicalPath }.getOrDefault(entityPath)
        val sharedRoot = runCatching { SHARED_STORAGE_ROOT.canonicalPath }.getOrDefault(SHARED_STORAGE_ROOT.absolutePath)
        canonical == sharedRoot || canonical.startsWith(sharedRoot + File.separator)
    }

    /** 列出项目指定相对路径下的所有文件与子目录（目录优先排序）。 */
    suspend fun listFiles(projectName: String, relativePath: String = ""): AppResult<List<WorkspaceFileItem>> =
        fileService.listFiles(projectName, relativePath)

    /** 读取文件内容（UTF-8，限制单文件最大读取大小）。 */
    suspend fun readFile(projectName: String, relativePath: String): AppResult<String> =
        fileService.readFile(projectName, relativePath)

    /** 写入文件内容（原子临时文件替换）。 */
    suspend fun writeFile(projectName: String, relativePath: String, content: String): AppResult<Unit> =
        fileService.writeFile(projectName, relativePath, content)

    /** 创建新文件（空文件）。 */
    suspend fun createFile(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createFile(projectName, relativePath)

    /** 创建新目录。 */
    suspend fun createDirectory(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.createDirectory(projectName, relativePath)

    /** 重命名文件或目录。 */
    suspend fun renameItem(projectName: String, oldRelativePath: String, newName: String): AppResult<Unit> =
        fileService.renameItem(projectName, oldRelativePath, newName)

    /** 删除文件或目录。 */
    suspend fun deleteItem(projectName: String, relativePath: String): AppResult<Unit> =
        fileService.deleteItem(projectName, relativePath)

    private fun isInside(root: File, candidate: File): Boolean =
        candidate.absolutePath == root.absolutePath ||
            candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun projectFromEntity(entity: WorkspaceEntity): WorkspaceProject? {
        val directory = File(entity.path)
        if (!directory.isDirectory) return null
        val type = detectProjectType(directory)
        val pkg = extractPackageName(directory, type)
        return WorkspaceProject(
            name = entity.name,
            path = entity.path,
            linuxPath = linuxPathFor(directory),
            sizeBytes = sizeOf(directory),
            ownsDirectory = entity.ownsDirectory,
            projectType = type,
            packageName = pkg,
        )
    }


    private fun detectProjectType(directory: File): ProjectType {
        readProjectTypeMetadata(directory)?.let { return it }
        return when {
            File(directory, "pubspec.yaml").exists() -> ProjectType.FLUTTER
            File(directory, "settings.gradle.kts").exists() ||
                File(directory, "app/build.gradle.kts").exists() ||
                File(directory, "build.gradle").exists() -> ProjectType.ANDROID
            // APK reverse engineering工程：优先使用导入元数据标记，再兼容 .apk/解包目录
            File(directory, "apk-info.properties").isFile ||
                directory.listFiles().orEmpty().any { it.isFile && it.extension.equals("apk", ignoreCase = true) } ||
                (File(directory, "unpacked").isDirectory && File(directory, "unpacked").listFiles().orEmpty()
                    .any { it.isFile && it.name.startsWith("classes") && it.extension == "dex" }) -> ProjectType.REVERSE
            else -> ProjectType.GENERAL
        }
    }

    private fun extractPackageName(directory: File, type: ProjectType): String {
        return runCatching {
            when (type) {
                ProjectType.ANDROID -> {
                    val appBuild = File(directory, "app/build.gradle.kts").takeIf { it.exists() }
                        ?: File(directory, "app/build.gradle").takeIf { it.exists() }
                    val content = appBuild?.readText()
                    val namespaceMatch = Regex("""(?:namespace|applicationId)\s*=\s*["']([^"']+)["']""").find(content ?: "")
                    namespaceMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.FLUTTER -> {
                    val pubspec = File(directory, "pubspec.yaml").takeIf { it.exists() }
                    val nameMatch = Regex("""name:\s*([a-zA-Z0-9_]+)""").find(pubspec?.readText() ?: "")
                    nameMatch?.groupValues?.get(1) ?: ""
                }
                ProjectType.REVERSE -> {
                    val info = File(directory, "apk-info.properties").takeIf { it.exists() }?.readText().orEmpty()
                    Regex("""packageName\s*=\s*(.+)""").find(info)?.groupValues?.get(1)?.trim() ?: ""
                }
                ProjectType.GENERAL -> ""
            }
        }.getOrDefault("")
    }

    private fun isValidGitUrl(url: String): Boolean =
        url.trim().let { value ->
            value.startsWith("https://") || value.startsWith("http://") ||
                value.startsWith("ssh://") || Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
        }

    private fun isValidGitUrlForTransport(url: String, transport: GitTransport): Boolean =
        url.trim().let { value ->
            when (transport) {
                GitTransport.HTTP -> value.startsWith("https://") || value.startsWith("http://")
                GitTransport.SSH -> value.startsWith("ssh://") ||
                    Regex("^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:.+").matches(value)
            }
        }

    private suspend fun cloneGitRepository(
        directory: File,
        url: String,
        cleanupOnFailure: Boolean,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val result = linuxRuntime.value.execute(
            ShellCommand(
                // --progress 让 git 在非 TTY 管道下也输出克隆进度（remote:/Receiving objects: 等）
                commandLine = "git clone --depth 1 --progress -- ${ShellQuote.of(url.trim())} ${ShellQuote.of(linuxPathFor(directory))}",
                timeoutMs = GIT_CLONE_TIMEOUT_SECONDS * 1_000L,
                onOutput = onProgress,
            ),
        )
        check(result.isSuccess) {
            if (cleanupOnFailure) SafeFileTree.delete(directory)
            val output = (result.stderr + "\n" + result.stdout).trim().takeLast(1200)
            "Git clone failed: ${output.ifBlank { "Ensure Git is installed and the repository URL and authentication are valid" }}"
        }
        SafeFileTree.delete(File(directory, ".git/hooks"))
    }

    private suspend fun importProject(
        name: String,
        directoryPath: String,
        projectType: ProjectType,
        source: ProjectImportSource,
        materialize: suspend (directory: File, cleanupOnFailure: Boolean) -> Unit,
    ): AppResult<WorkspaceProject> {
        return try {
            val safeName = name.trim()
            require(isValidProjectName(safeName)) { "Name must start with a letter or number and may contain only letters, numbers, dots, underscores, and hyphens" }
            require(safeName != "sdcard") { "sdcard is reserved for shared system storage" }
            check(workspaceDao.findByName(safeName) == null) { "Project already exists: $safeName" }
            pathManager.workspaceDir.mkdirs()
            val base = pathManager.workspaceDir.canonicalFile
            check(base.isDirectory || base.mkdirs()) { "Internal sandbox directory is unavailable" }
            val requested = directoryPath.trim().replace('\\', '/').removePrefix("/workspace/").trim('/')
            val relative = requested.ifBlank { safeName }
            require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Linked directory contains an invalid path" }
            val directory = File(base, relative).canonicalFile
            check(isInside(base, directory) && directory != base) { "Linked directory is outside the allowed path" }
            val duplicate = workspaceDao.listAll().any {
                runCatching { File(it.path).canonicalFile == directory }.getOrDefault(false)
            }
            check(!duplicate) { "This directory is already linked to another project" }
            val existed = directory.exists()
            require(!existed || directory.isDirectory) { "Import target is not a directory" }
            require(!existed || directory.listFiles().orEmpty().isEmpty()) { "Import target directory must be empty" }
            if (!existed) require(directory.mkdirs()) { "Unable to create import directory" }
            materialize(directory, !existed)
            writeProjectTypeMetadata(directory, projectType, source)
            workspaceDao.upsert(
                WorkspaceEntity(safeName, directory.absolutePath, System.currentTimeMillis(), ownsDirectory = !existed),
            )
            val createdEntity = workspaceDao.findByName(safeName)
            if (createdEntity == null) {
                AppResult.Failure(AppError(ErrorCode.IO, "Failed to query project after writing", null))
            } else {
                val created = projectFromEntity(createdEntity)
                if (created == null) {
                    AppResult.Failure(AppError(ErrorCode.IO, "Failed to convert project data", null))
                } else {
                    AppResult.Success(created)
                }
            }
        } catch (throwable: Throwable) {
            AppResult.Failure(AppError(ErrorCode.IO, throwable.message ?: "Failed to import project", throwable))
        }
    }

    private fun extractProjectArchive(source: ProjectArchiveSource, directory: File) {
        require(source.fileName.endsWith(".zip", ignoreCase = true)) { "Local import currently supports ZIP project archives only" }
        val input = context.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: error("Unable to read the selected project archive (URI permission may have expired; select it again)")
        extractProjectArchive(input, source.fileName, directory)
    }

    internal fun extractProjectArchive(input: InputStream, fileName: String, directory: File) {
        require(fileName.endsWith(".zip", ignoreCase = true)) { "Local import currently supports ZIP project archives only" }
        val staging = File(directory, IMPORT_STAGING_DIRECTORY).canonicalFile
        check(isInside(directory.canonicalFile, staging)) { "Import staging directory is outside the allowed path" }
        SafeFileTree.delete(staging)
        check(staging.mkdirs()) { "Unable to create import staging directory" }
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Archive contains too many files" }
                    val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                    require(normalizedName.isNotBlank()) { "Archive contains an empty path" }
                    require(normalizedName.split('/').none { it == ".." }) { "Archive contains a path traversal entry: ${entry.name}" }
                    val target = File(staging, normalizedName).canonicalFile
                    require(isInside(staging, target) && target != staging) { "Archive contains a path traversal entry: ${entry.name}" }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Unable to create directory: ${entry.name}" }
                    } else {
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                            "Unable to create directory: ${entry.name}"
                        }
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                require(entryBytes <= MAX_ARCHIVE_ENTRY_BYTES) { "Archive entry is too large: ${entry.name}" }
                                require(totalBytes <= MAX_ARCHIVE_TOTAL_BYTES) { "Extracted archive is too large" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(entryCount > 0) { "Project archive is empty" }
            val meaningful = staging.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            val contentRoot = meaningful.singleOrNull()?.takeIf { it.isDirectory } ?: staging
            val children = contentRoot.listFiles().orEmpty().filterNot { it.name == "__MACOSX" }
            require(children.isNotEmpty()) { "Project archive contains no importable files" }
            children.forEach { child ->
                val destination = File(directory, child.name).canonicalFile
                check(isInside(directory.canonicalFile, destination) && !destination.exists()) { "Import file conflict: ${child.name}" }
                check(child.renameTo(destination)) { "Unable to write imported file: ${child.name}" }
            }
        } finally {
            SafeFileTree.delete(staging)
        }
    }

    private fun writeProjectZip(projectDir: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            projectDir.walkTopDown()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it != projectDir && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    val relative = file.toRelativeString(projectDir).replace(File.separatorChar, '/')
                    if (relative == UNLINKED_MARKER || relative == IMPORT_STAGING_DIRECTORY) return@forEach
                    val entryName = if (file.isDirectory) "$relative/" else relative
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun writeProjectTypeMetadata(directory: File, type: ProjectType, source: ProjectImportSource) {
        File(directory, PROJECT_METADATA_FILE).writeText(
            "type=${type.name}\nsource=${source.name}\nimportedAt=${System.currentTimeMillis()}\n",
            Charsets.UTF_8,
        )
    }

    private fun readProjectTypeMetadata(directory: File): ProjectType? = runCatching {
        val metadata = File(directory, PROJECT_METADATA_FILE)
        if (!metadata.isFile) return@runCatching null
        val typeName = metadata.useLines { lines ->
            lines.firstOrNull { it.startsWith("type=") }?.substringAfter("type=")?.trim()
        }
        ProjectType.entries.firstOrNull { it.name == typeName }
    }.getOrNull()

    private suspend fun executeTemplateHook(
        templateId: String,
        stage: String,
        relativePath: String,
        projectDir: File,
        values: Map<String, String>,
    ) {
        val hookFile = File(projectDir, ".taixu-template-$stage-${UUID.randomUUID()}.sh")
        try {
            hookFile.writeBytes(projectTemplateEngine.readHook(templateId, relativePath))
            hookFile.setExecutable(true)
            val result = linuxRuntime.value.execute(
                ShellCommand(
                    commandLine = "sh ${ShellQuote.of(linuxPathFor(hookFile))}",
                    workingDirectory = linuxPathFor(projectDir),
                    environment = values.mapKeys { (name, _) -> "TAIXU_VAR_${name.uppercase()}" } +
                        ("TAIXU_PROJECT_DIR" to linuxPathFor(projectDir)),
                    timeoutMs = TEMPLATE_HOOK_TIMEOUT_MS,
                ),
            )
            check(result.isSuccess) {
                "Template script failed ($stage): ${result.stderr.ifBlank { result.stdout }.takeLast(2_000)}"
            }
        } finally {
            hookFile.delete()
        }
    }

    // ==================== APK reverse engineering模板 ====================

    private data class ImportedApk(
        val packageName: String,
        val apkFileName: String,
        val sourceLabel: String,
        val sourceKind: String,
    )

    /**
     * APK reverse engineering模板初始化：把安装包导入工程目录并做第一层"解包"。
     *
     * 产物结构（以工程名 [name] 为例）：
     * ```
     * <project>/
     * ├── <name>.apk            # 原始安装包（可直接交给 jadx / apktool / MT 管理器）
     * ├── unpacked/             # 标准 ZIP 解包产物（dex / res / assets / lib / 二进制 AXML）
     * │   ├── AndroidManifest.xml
     * │   ├── classes.dex
     * │   ├── resources.arsc
     * │   └── ...
     * ├── apk-info.properties   # 来源与元数据（工程包名读取处）
     * └── REVERSE.md            # 逆向工作流指引（jadx / apktool / MCP）
     * ```
     */
    private fun importApkForReverse(
        projectDir: File,
        name: String,
        apkSource: ApkImportSource?,
    ): ImportedApk {
        requireNotNull(apkSource) { "APK reverse engineering模板必须选择安装包来源（installed app or APK file）" }
        projectDir.mkdirs()

        // 1. 解析来源并拷贝安装包到工程目录
        val apkFileName: String
        val sourceLabel: String
        val sourceKind: String
        val packageHint: String
        val apkFile: File
        when (apkSource) {
            is ApkImportSource.FromInstalledApp -> {
                val info = runCatching {
                    context.packageManager.getApplicationInfo(apkSource.packageName, 0)
                }.getOrElse { error("Unable to read installed app information: ${apkSource.packageName}") }
                val source = File(info.sourceDir)
                require(source.isFile) { "App package is unreadable: ${source.absolutePath}" }
                apkFileName = "${apkSource.packageName}.apk"
                sourceLabel = apkSource.appLabel
                sourceKind = "installed-app"
                packageHint = apkSource.packageName
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK output path is outside the allowed directory" }
                source.copyTo(apkFile, overwrite = true)
            }
            is ApkImportSource.FromFileUri -> {
                val uri = Uri.parse(apkSource.uri)
                val safeBase = apkSource.fileName
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .trim('.')
                    .ifBlank { "target" }
                val displayName = if (safeBase.endsWith(".apk", ignoreCase = true)) safeBase else "$safeBase.apk"
                apkFileName = displayName
                sourceLabel = apkSource.displayName.ifBlank { displayName }
                sourceKind = "file-uri"
                packageHint = ""
                apkFile = File(projectDir, apkFileName).canonicalFile
                check(isInside(projectDir.canonicalFile, apkFile)) { "APK output path is outside the allowed directory" }
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Unable to read the selected APK (URI permission may have expired; select it again)")
                input.use { source ->
                    apkFile.outputStream().use { output -> source.copyTo(output) }
                }
            }
        }
        require(apkFile.isFile && apkFile.length() > 0) { "Failed to import package: $apkFileName" }

        // 2. 标准 ZIP 解包 -> unpacked/
        val unpackedDir = File(projectDir, "unpacked").apply { mkdirs() }
        unpackApk(apkFile, unpackedDir)

        // 3. 写入元数据与逆向工作流指引
        File(projectDir, "apk-info.properties").writeText(
            buildString {
                appendLine("apk=${apkFile.name}")
                appendLine("apkSizeBytes=${apkFile.length()}")
                appendLine("source=$sourceKind")
                appendLine("sourceLabel=$sourceLabel")
                appendLine("packageName=$packageHint")
                appendLine("importedAt=${System.currentTimeMillis()}")
            },
            Charsets.UTF_8,
        )
        writeReverseReadme(projectDir, name, apkFile.name, unpackedDir, sourceLabel)

        return ImportedApk(
            packageName = packageHint,
            apkFileName = apkFileName,
            sourceLabel = sourceLabel,
            sourceKind = sourceKind,
        )
    }

    /** 用标准 ZIP 读取器把 APK 逐条目解包到 [unpackedDir]（防 zip-slip 路径穿越）。 */
    private fun unpackApk(apkFile: File, unpackedDir: File) {
        val unpackedCanonical = unpackedDir.canonicalFile
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val rawName = entry.name.replace('\\', '/')
                // 防 zip-slip：拒绝绝对路径与 .. 穿越
                if (rawName.startsWith("/") || rawName.split('/').any { it == ".." }) return@forEach
                val target = File(unpackedDir, rawName)
                if (!isInside(unpackedCanonical, target.canonicalFile)) return@forEach
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** Generate the reverse-engineering README linking to TaiXu's built-in jadx, apktool, and reverse-engineering MCP capabilities. */
    private fun writeReverseReadme(
        projectDir: File,
        name: String,
        apkFileName: String,
        unpackedDir: File,
        sourceLabel: String,
    ) {
        val entryCount = unpackedDir.walkTopDown().count { it.isFile }
        File(projectDir, "REVERSE.md").writeText(
            """
            # $name · APK Reverse-Engineering Project

            > Source: $sourceLabel
            > Imported: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}

            ## Project Structure

            | Path | Description |
            | :--- | :--- |
            | `$apkFileName` | Original APK (unmodified) |
            | `unpacked/` | First-level ZIP extraction ($entryCount files): `classes.dex`, `resources.arsc`, `AndroidManifest.xml` (binary AXML), `res/`, `assets/`, `lib/`, etc. |
            | `apk-info.properties` | Source and metadata |

            ## Next Steps: Continue Analysis in the TaiXu Terminal or Agent

            The sandbox includes a reverse-engineering toolchain (available after installing the Android & Mobile Development bundle or the apktool bundle):

            ```bash
            # 1) DEX -> Java source (recommended for readability)
            jadx -d java-src "$apkFileName"

            # 2) Extract resources and Smali (rebuildable)
            apktool d "$apkFileName" -o apktool-out
            #   Rebuild: apktool b apktool-out -o rebuilt.apk

            # 3) Decode the binary manifest (with apktool output)
            #    aapt dump badging "$apkFileName"   # package / version / permissions
            #    aapt dump xmltree "$apkFileName" AndroidManifest.xml
            ```

            You can also enable the built-in **Android reverse-engineering MCP service** (`mcp_apktool`) in MCP Settings:
            `decode_apk` / `analyze_manifest` / `extract_strings` / `search_smali` / `build_apk` / `sign_apk`。

            ## What to Inspect

            - **AndroidManifest.xml**: exported status of the four component types, permissions, and Application class
            - **classes.dex**: core application logic (after decompiling with jadx, search for URLs, keys, and encryption/decryption patterns)
            - **lib/**: native .so libraries (analyze with IDA or the Xuanxing reverse-engineering SOMCP)
            - **assets/** and **res/**: bundled resources, configuration files, and possible app-protection indicators

            > Note: `unpacked/AndroidManifest.xml` may look like garbled text because AXML is a binary format.
            > Decode it with `apktool d` or `aapt dump xmltree`.

            ## Identifying App Protection (when jadx cannot show the real code)

            If decompiling `unpacked/classes.dex` shows only a stub loader, the APK is protected. The library names under `lib/` can help identify the vendor:

            | Library indicators | Protection vendor |
            | :--- | :--- |
            | `libjiagu.so` / `libjiagu_art.so` | **360 protection** (entry point `com.stub.StubApp`) |
            | `libDexHelper.so` / `libSecShell.so` / `libsecexe.so` | **Bangcle (SecNeo)** (entry point `com.secneo.apkwrapper.ApplicationWrapper`) |
            | `libshellx-super*.so` / `libtup.so` / `libexec.so` | **Tencent Legu / Yu Security** (`com.tencent.StubShell`) |
            | `libnesec.so` | **NetEase Yidun** (`com.netease.nis.wrapper`) |
            | `ijiami.ajm` / `libexecmain.so` / `assets/ijm_lib/` | **Ijiami** (entry point `s.h.e.l.l.S`) |
            | `libbaiduprotect.so` / `assets/baiduprotect*` | **Baidu protection** |
            | `libzuma.so` / `assets/qihoo/` | **Alibaba Juxian** |
            | `libddog.so` / `libchaosvmp.so` | **Nagain (VMP protection)** |
            | `libx3g.so` | **Dingxiang** |
            | `libkwscmm.so` / `libkwsgmain.so` | **Jiwei** |
            | `libnqshield.so` / `libmobisec.so` / `libkiroro.so` | NetQin, older Alibaba protection, Kiro, etc. |

            Additional indicators include files under `assets/` (`ijiami.dat`, `bangcleplugin/`, `libjiagu*`, `appsealing*`) and the `android:name` entry in AndroidManifest.

            ## Protection Removal Guidance

            | Protection type | Indicators | Suggested approach |
            | :--- | :--- | :--- |
            | **First-generation** (whole-DEX encryption) | jadx shows only a stub | **General deprotection**: FRIDA-DEXDump (`frida -U -f package.name -l frida-dexdump.js`), BlackDex / FullDump (one-tap, no root), or an MT Manager deprotection plugin |
            | **Second-generation** (method/function extraction) | Method bodies are restored at runtime | **Invoke-based deprotection**: FART / Youpk / Reflection Master (use a custom ROM or Xposed-level framework to trigger each method before dumping) |
            | **VMP protection** (instruction virtualization, e.g. Nagain chaosvmp) | Code is virtualized | Full removal is difficult; usually **debug key logic dynamically** with Frida hooks or Unidbg emulation |

            After dumping, `classesN.dex` may have a damaged header or checksum. Repair the DEX header before decompiling with `jadx`. To modify behavior, many protected APKs can be patched in the corresponding smali or native libraries and repackaged.
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }

    /**
     * 把工程内导入的 APK 同步导出到宿主公共下载目录（best-effort，供宿主侧 MT 管理器等外部工具直接读取；
     * Android 11+ requires “All files access” permission. If it is not granted, export is silently skipped and project creation is unaffected).
     */
    private fun exportApkToDownload(apkFileName: String, projectDir: File, projectName: String) {
        runCatching {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists() && !downloadDir.mkdirs()) return
            val source = File(projectDir, apkFileName)
            if (!source.isFile) return
            source.copyTo(File(downloadDir, "$projectName.apk"), overwrite = true)
        }
    }

    private fun linuxPathFor(directory: File): String {
        val canonical = directory.canonicalFile
        val internal = pathManager.workspaceDir.canonicalFile
        val shared = SHARED_STORAGE_ROOT.canonicalFile
        return when {
            isInside(internal, canonical) -> "/workspace/${canonical.toRelativeString(internal).replace(File.separatorChar, '/')}"
            isInside(shared, canonical) -> "/sdcard/${canonical.toRelativeString(shared).replace(File.separatorChar, '/')}"
            else -> error("Directory is outside the allowed linked workspace")
        }.trimEnd('/')
    }

    private fun sizeOf(file: File): Long = file.walkTopDown()
        .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) }
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .sumOf { it.length() }

    private fun isValidProjectName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_PROJECT_NAME_LENGTH) return false
        if (!name.first().isLetterOrDigit()) return false
        return name.drop(1).all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    companion object {
        private const val UNLINKED_MARKER = ".taixu-unlinked-project"
        private const val PROJECT_METADATA_FILE = ".taixu-project.properties"
        private const val IMPORT_STAGING_DIRECTORY = ".taixu-import-staging"
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_ARCHIVE_ENTRY_BYTES = 1024L * 1024L * 1024L
        private const val MAX_ARCHIVE_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
        private const val GIT_CLONE_TIMEOUT_SECONDS = 15 * 60L
        private const val TEMPLATE_HOOK_TIMEOUT_MS = 60_000L
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        const val MAX_PROJECT_NAME_LENGTH = 64
        const val MAX_FILE_READ_BYTES = 4 * 1024 * 1024L // 4 MB
        const val MAX_FILE_WRITE_CHARS = 4 * 1024 * 1024 // 4 M 字符
        val SHARED_STORAGE_ROOT: File = File("/storage/emulated/0")
    }
}
