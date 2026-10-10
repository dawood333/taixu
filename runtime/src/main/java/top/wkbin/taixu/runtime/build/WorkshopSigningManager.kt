package top.wkbin.taixu.runtime.build

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.datastore.WorkshopKeystore
import top.wkbin.taixu.core.datastore.WorkshopPreferences
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer
import top.wkbin.taixu.runtime.shell.ShellCommand

/** 工坊 Android 应用构建类型。 */
enum class WorkshopBuildType(val displayName: String) {
    DEBUG("Debug"),
    RELEASE("Release"),
}

/**
 * 工坊 Android 签名（Keystore）管理器。
 *
 * - 主副本保存在宿主应用私有目录（跨发行版重装不丢失）；
 * - 创建通过沙箱内 keytool（复用工坊配置的 JDK）生成 PKCS12 密钥库；
 * - 导入通过 SAF URI 复制宿主文件，并用 keytool -list 校验口令；
 * - Release 构建前由 [prepareReleaseSigning] 把密钥库同步进当前沙箱
 *   /opt/taixu/keystores/，并安装 Gradle init 签名策略 + 注入环境变量。
 */
class WorkshopSigningManager(
    private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val pathManager: RuntimePathManager,
    private val preferences: WorkshopPreferences,
    private val assetSynchronizer: RuntimeAssetSynchronizer,
) {
    val keystores: Flow<List<WorkshopKeystore>> = preferences.keystores

    /** 宿主主副本目录（应用私有目录，不随沙箱销毁）。 */
    private fun hostKeystoreDir(): File = File(pathManager.baseDir, "workshop/keystores")

    /** 沙箱内可见的密钥库目录（PRoot 绑定 distroDir/opt/taixu -> /opt/taixu）。 */
    private fun sandboxKeystoreDir(distroId: String): File =
        File(pathManager.taixuRootDir(distroId), "keystores")

    /** keytool 在沙箱内可访问的密钥库路径（必须使用沙箱内路径，而非宿主绝对路径）。 */
    private fun sandboxKeystorePath(fileName: String): String = "/opt/taixu/keystores/$fileName"

    private suspend fun javaHome(): String =
        preferences.javaPath.first().ifBlank { "/opt/taixu/toolchains/android/jdk" }

    suspend fun createKeystore(
        name: String,
        alias: String,
        storePassword: String,
        keyPassword: String,
        validityYears: Int,
        organization: String,
    ): AppResult<WorkshopKeystore> = withContext(Dispatchers.IO) {
        val safeName = name.trim()
        val safeAlias = alias.trim().ifBlank { safeName }
        if (safeName.isBlank()) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Signing key name cannot be empty"))
        }
        if (storePassword.length < 6) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Keystore password must contain at least 6 characters"))
        }
        if (keyPassword.isNotBlank() && keyPassword.length < 6) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Key password must contain at least 6 characters"))
        }

        val distroId = linuxRuntime.activeDistroId.value
        if (distroId.isBlank()) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Environment incomplete"))
        }

        val fileName = "${sanitizeFileName(safeName)}-${UUID.randomUUID().toString().substring(0, 8)}.p12"
        val sandboxDir = sandboxKeystoreDir(distroId).apply { mkdirs() }
        val sandboxFile = File(sandboxDir, fileName)
        sandboxFile.delete()
        val dname = buildString {
            append("CN=").append(safeName)
            if (organization.isNotBlank()) append(", O=").append(organization.trim())
            append(", C=CN")
        }
        val command = buildCreateKeystoreCommand(
            javaHome = javaHome(),
            keystorePath = sandboxKeystorePath(fileName),
            alias = safeAlias,
            storePassword = storePassword,
            keyPassword = keyPassword.ifBlank { storePassword },
            validityYears = validityYears,
            dname = dname,
        )
        val result = linuxRuntime.execute(
            ShellCommand(commandLine = command, timeoutMs = 120_000L),
        )
        if (!result.isSuccess || !sandboxFile.isFile || sandboxFile.length() == 0L) {
            sandboxFile.delete()
            val rawReason = (result.stderr + "\n" + result.stdout).trim()
            val reason = if (result.exitCode == 127 || rawReason.contains("No such file", ignoreCase = true) || rawReason.contains("not found", ignoreCase = true)) {
                "Environment incomplete"
            } else {
                rawReason.take(400).ifBlank { "exit ${result.exitCode}" }
            }
            return@withContext AppResult.Failure(
                AppError(ErrorCode.UNKNOWN, reason),
            )
        }
        // 复制主副本到宿主私有目录，并登记元数据。
        val hostFile = File(hostKeystoreDir().apply { mkdirs() }, fileName)
        runCatching { sandboxFile.copyTo(hostFile, overwrite = true) }
            .onFailure {
                return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Failed to save primary/backup signing key: ${it.message}"))
            }
        val record = WorkshopKeystore(
            id = UUID.randomUUID().toString(),
            name = safeName,
            fileName = fileName,
            alias = safeAlias,
            storePassword = storePassword,
            keyPassword = keyPassword.ifBlank { storePassword },
            validityYears = validityYears.coerceIn(1, 100),
            organization = organization.trim(),
            createdAtMillis = System.currentTimeMillis(),
        )
        saveRecord(record)
        AppResult.Success(record)
    }

    suspend fun importKeystore(
        uri: String,
        name: String,
        alias: String,
        storePassword: String,
        keyPassword: String,
    ): AppResult<WorkshopKeystore> = withContext(Dispatchers.IO) {
        val safeName = name.trim()
        if (safeName.isBlank()) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Signing key name cannot be empty"))
        }
        if (storePassword.isBlank()) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Enter the keystore password"))
        }
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null) {
            return@withContext AppResult.Failure(AppError(ErrorCode.UNKNOWN, "Invalid signing file path"))
        }
        val fileName = "${sanitizeFileName(safeName)}-${UUID.randomUUID().toString().substring(0, 8)}.p12"
        val hostFile = File(hostKeystoreDir().apply { mkdirs() }, fileName)
        try {
            context.contentResolver.openInputStream(parsed)?.use { input ->
                hostFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Unable to read the selected signing file"))
        } catch (throwable: Throwable) {
            return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Failed to read signing file: ${throwable.message}"))
        }
        if (!hostFile.isFile || hostFile.length() == 0L) {
            hostFile.delete()
            return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Signing file is empty"))
        }
        // 用沙箱内 keytool 校验文件确实是可用密钥库且口令正确。
        val distroId = linuxRuntime.activeDistroId.value
        val sandboxDir = sandboxKeystoreDir(distroId).apply { mkdirs() }
        val sandboxFile = File(sandboxDir, fileName)
        runCatching { hostFile.copyTo(sandboxFile, overwrite = true) }
        val verify = linuxRuntime.execute(
            ShellCommand(
                commandLine = "'${javaHome()}/bin/keytool' -list" +
                    " -keystore '${sandboxKeystorePath(fileName)}'" +
                    " -storepass '${storePassword.replace('\'', ' ')}'",
                timeoutMs = 60_000L,
            ),
        )
        if (!verify.isSuccess) {
            sandboxFile.delete()
            hostFile.delete()
            val rawReason = (verify.stderr + "\n" + verify.stdout).trim()
            val reason = if (verify.exitCode == 127 || rawReason.contains("not found", ignoreCase = true)) {
                "Environment is missing the keytool toolchain"
            } else {
                "Incorrect password or invalid keystore file"
            }
            return@withContext AppResult.Failure(
                AppError(ErrorCode.UNKNOWN, "Signing key validation failed ($reason)"),
            )
        }
        // 从 keytool 输出中解析首个别名作为默认 alias。
        val resolvedAlias = alias.trim().ifBlank {
            verify.stdout.lineSequence()
                .firstOrNull { it.trim().endsWith(", PrivateKeyEntry") }
                ?.substringBefore(',')
                ?.trim()
                .orEmpty()
        }
        val record = WorkshopKeystore(
            id = UUID.randomUUID().toString(),
            name = safeName,
            fileName = fileName,
            alias = resolvedAlias,
            storePassword = storePassword,
            keyPassword = keyPassword.ifBlank { storePassword },
            validityYears = WorkshopKeystore.DEFAULT_VALIDITY_YEARS,
            organization = "",
            createdAtMillis = System.currentTimeMillis(),
        )
        saveRecord(record)
        AppResult.Success(record)
    }

    suspend fun deleteKeystore(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val current = preferences.keystores.first()
        val target = current.firstOrNull { it.id == id }
            ?: return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Signing key does not exist or has been deleted"))
        preferences.setKeystores(current.filterNot { it.id == id })
        File(hostKeystoreDir(), target.fileName).delete()
        val sandboxFile = File(sandboxKeystoreDir(linuxRuntime.activeDistroId.value), target.fileName)
        runCatching { sandboxFile.delete() }
        AppResult.Success(Unit)
    }

    /**
     * Release 构建前的签名准备：
     * 1. 同步资产脚本，保证 /opt/taixu/scripts/taixu-release-signing.gradle 存在；
     * 2. 把密钥库主副本复制进当前沙箱 /opt/taixu/keystores/；
     * 3. 把签名 init 脚本安装到 ARM64 (/root/.gradle) 与 QEMU (compat) 两套 Gradle 用户目录；
     * 4. 返回注入构建命令的 TAIXU_KEYSTORE_* 环境变量。
     */
    suspend fun prepareReleaseSigning(keystore: WorkshopKeystore): AppResult<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val hostFile = File(hostKeystoreDir(), keystore.fileName)
            if (!hostFile.isFile || hostFile.length() == 0L) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.IO, "Primary or backup signing file is missing. Re-import signing key ${keystore.name}."),
                )
            }
            val distroId = linuxRuntime.activeDistroId.value
            runCatching { assetSynchronizer.syncAssetsToDistro(distroId) }
            val sandboxDir = sandboxKeystoreDir(distroId).apply { mkdirs() }
            val sandboxFile = File(sandboxDir, keystore.fileName)
            runCatching { hostFile.copyTo(sandboxFile, overwrite = true) }
                .onFailure {
                    return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Failed to sync signing key to the sandbox: ${it.message}"))
                }
            if (!sandboxFile.isFile || sandboxFile.length() != hostFile.length()) {
                return@withContext AppResult.Failure(AppError(ErrorCode.IO, "Signing data could not be fully synchronized into the sandbox"))
            }
            // 安装签名 init 脚本：兼容 ARM64 主路径与 QEMU x86_64 兼容路径。
            val scriptSource = File(pathManager.taixuScriptsDir(distroId), SIGNING_INIT_SCRIPT_NAME)
            if (!scriptSource.isFile) {
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.IO, "Signing policy script $SIGNING_INIT_SCRIPT_NAME is missing from the sandbox. Sync the Workshop assets first."),
                )
            }
            val scriptContent = scriptSource.readText(Charsets.UTF_8)
            val initTargets = listOf(
                File(pathManager.rootfsDir(distroId), "root/.gradle/init.d/$SIGNING_INIT_SCRIPT_NAME"),
                File(pathManager.taixuRootDir(distroId), "compat/x86_64/cache/gradle/init.d/$SIGNING_INIT_SCRIPT_NAME"),
            )
            initTargets.forEach { target ->
                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(scriptContent.removePrefix("\uFEFF").replace("\r\n", "\n"), Charsets.UTF_8)
                }
            }
            AppResult.Success(
                mapOf(
                    "TAIXU_KEYSTORE_FILE" to "/opt/taixu/keystores/${keystore.fileName}",
                    "TAIXU_KEYSTORE_STORE_PASSWORD" to keystore.storePassword,
                    "TAIXU_KEYSTORE_KEY_ALIAS" to keystore.alias,
                    "TAIXU_KEYSTORE_KEY_PASSWORD" to keystore.keyPassword,
                ),
            )
        }

    private suspend fun saveRecord(record: WorkshopKeystore) {
        val current = preferences.keystores.first()
        preferences.setKeystores(current + record)
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "keystore" }

    private companion object {
        const val SIGNING_INIT_SCRIPT_NAME = "taixu-release-signing.gradle"
    }
}

internal fun buildCreateKeystoreCommand(
    javaHome: String,
    keystorePath: String,
    alias: String,
    storePassword: String,
    keyPassword: String,
    validityYears: Int,
    dname: String,
): String = buildString {
    append('\'').append(javaHome).append("/bin/keytool' -genkeypair -v")
    append(" -keystore '").append(keystorePath).append('\'')
    append(" -storetype PKCS12")
    append(" -alias '").append(alias.replace('\'', ' ')).append('\'')
    append(" -keyalg RSA -keysize 2048")
    append(" -validity ").append(validityYears.coerceIn(1, 100) * 365)
    append(" -storepass '").append(storePassword.replace('\'', ' ')).append('\'')
    append(" -keypass '").append(keyPassword.replace('\'', ' ')).append('\'')
    append(" -dname \"").append(dname.replace('\"', ' ')).append('\"')
}
