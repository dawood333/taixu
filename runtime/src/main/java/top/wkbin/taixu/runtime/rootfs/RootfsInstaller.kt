package top.wkbin.taixu.runtime.rootfs

import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.XZInputStream
import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.runtime.DistributionSpec
import top.wkbin.taixu.runtime.DownloadProgress
import top.wkbin.taixu.runtime.RegistryRoute
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.RootfsUpdateInfo
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class RootfsInstaller(
    private val pathManager: RuntimePathManager,
    private val tarStreamExtractor: TarStreamExtractor,
    private val rootfsValidator: RootfsValidator,
    private val logger: AppLogger,
    private val ociRegistryClient: OciRegistryClient,
    private val lxcImagesClient: LxcImagesClient,
) {
    private var pendingUpdateBackup: File? = null
    private var pendingUpdateVersion: String? = null
    private var pendingUpdateDigest: String? = null

    suspend fun checkForUpdate(
        distribution: DistributionSpec,
        route: RegistryRoute,
    ): RootfsUpdateInfo = withContext(Dispatchers.IO) {
        val latest = ociRegistryClient.resolve(distribution, route)
        val currentDigest = pathManager.rootfsDigest(distribution.id)
        RootfsUpdateInfo(
            distroId = distribution.id,
            imageReference = distribution.imageReference,
            currentVersion = pathManager.rootfsVersion(distribution.id),
            currentDigest = currentDigest,
            latestDigest = latest.digest,
            hasUpdate = currentDigest.isNullOrBlank() || !currentDigest.equals(latest.digest, ignoreCase = true),
        )
    }

    suspend fun installOci(
        distribution: DistributionSpec,
        route: RegistryRoute,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): AppResult<File> = withContext(Dispatchers.IO) {
        val distroId = distribution.id.lowercase()
        val distroTargetDir = pathManager.rootfsDir(distroId)
        val staging = prepareStaging(distroId)
        recoverInterruptedUpdate(distroId)
        try {
            val image = pullInto(distribution, route, staging, onProgress)
            rootfsValidator.validate(staging)
            if (pathManager.isDistroInstalled(distroId)) preserveUserDirectories(distroTargetDir, staging)
            replaceRootfs(distroId, staging, retainBackup = false)
            markInstalled(distroId, image)
            pathManager.homeDir(distroId).mkdirs()
            logger.i("Installed ${distribution.imageReference} through OCI into $distroTargetDir")
            AppResult.Success(distroTargetDir)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(pathManager.stagingRootfsDir(distroId))
            logger.e("Failed to install OCI rootfs for $distroId", throwable)
            failure("OCI RootFS installation failed ($distroId)", throwable)
        }
    }

    suspend fun importArchive(distroId: String, archive: File): AppResult<File> = withContext(Dispatchers.IO) {
        val safeId = distroId.lowercase().trim()
        require(safeId.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))) { "Invalid Linux distribution ID" }
        require(archive.isFile) { "Import file does not exist" }
        val staging = prepareStaging(safeId)
        try {
            BufferedInputStream(archive.inputStream()).use { buffered ->
                buffered.mark(8)
                val magic = ByteArray(4)
                buffered.read(magic)
                buffered.reset()
                require(!(magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())) {
                    "ZIP cannot reliably preserve Linux permissions and symlinks. Use tar, tar.gz, tar.xz, or tar.zst instead."
                }
                val stream = when {
                    magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GZIPInputStream(buffered)
                    magic[0] == 0x28.toByte() && magic[1] == 0xb5.toByte() -> ZstdInputStream(buffered)
                    magic[0] == 0xfd.toByte() && magic[1] == 0x37.toByte() -> XZInputStream(buffered)
                    else -> buffered
                }
                stream.use { tarStreamExtractor.extract(it, staging, handleWhiteouts = false) }
            }
            val actualRoot = normalizeImportedRoot(staging)
            if (actualRoot != staging) {
                val normalized = File(staging.parentFile, "rootfs.normalized")
                SafeFileTree.delete(normalized)
                check(actualRoot.renameTo(normalized)) { "Unable to organize imported RootFS" }
                SafeFileTree.delete(staging)
                check(normalized.renameTo(staging)) { "Unable to commit imported RootFS" }
            }
            rootfsValidator.validate(staging)
            val target = pathManager.rootfsDir(safeId)
            if (pathManager.isDistroInstalled(safeId)) preserveUserDirectories(target, staging)
            replaceRootfs(safeId, staging, retainBackup = false)
            markInstalled(safeId, OciRegistryClient.ImageInfo("local-import", "local-${archive.length()}-${archive.lastModified()}"))
            pathManager.homeDir(safeId).mkdirs()
            AppResult.Success(target)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(staging)
            logger.e("Failed to import rootfs archive for $safeId", throwable)
            failure("Failed to import RootFS ($safeId)", throwable)
        }
    }

    private fun normalizeImportedRoot(staging: File): File {
        val children = staging.listFiles().orEmpty().filterNot { it.name == ".taixu" }
        if (children.size == 1 && children[0].isDirectory) {
            val nested = children[0]
            val hasRootMarker = File(nested, "etc/os-release").isFile || File(nested, "usr/lib/os-release").isFile
            if (hasRootMarker) return nested
        }
        return staging
    }

    suspend fun updateOci(
        distribution: DistributionSpec,
        route: RegistryRoute,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): AppResult<File> = withContext(Dispatchers.IO) {
        val distroId = distribution.id.lowercase()
        val distroTargetDir = pathManager.rootfsDir(distroId)
        if (!pathManager.isDistroInstalled(distroId)) return@withContext installOci(distribution, route, onProgress)
        try {
            val staging = prepareStaging(distroId)
            val image = pullInto(distribution, route, staging, onProgress)
            rootfsValidator.validate(staging)
            preserveUserDirectories(distroTargetDir, staging)
            replaceRootfs(distroId, staging, retainBackup = true)
            pendingUpdateVersion = image.version
            pendingUpdateDigest = image.digest
            pathManager.rootfsUpdatePendingMarker(distroId).writeText(
                "rootfs-version=${image.version}\nrootfs-digest=${image.digest}\n",
            )
            AppResult.Success(distroTargetDir)
        } catch (cancellation: CancellationException) {
            rollbackPendingUpdate(distroId)
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(pathManager.stagingRootfsDir(distroId))
            if (pendingUpdateBackup != null || pathManager.rootfsPreviousDir(distroId).exists()) {
                rollbackPendingUpdate(distroId)
            }
            logger.e("Failed to update OCI rootfs for $distroId", throwable)
            failure("OCI RootFS update failed ($distroId)", throwable)
        }
    }

    suspend fun uninstallDistro(distroId: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val safeId = distroId.lowercase().trim()
        val dir = pathManager.distroDir(safeId)
        if (!dir.exists()) {
            return@withContext AppResult.Success(Unit)
        }
        try {
            SafeFileTree.delete(dir)
            logger.i("Uninstalled distro $safeId from disk")
            AppResult.Success(Unit)
        } catch (e: Exception) {
            logger.e("Failed to uninstall distro $safeId", e)
            AppResult.Failure(AppError(ErrorCode.IO, "Failed to uninstall distribution: ${e.message}", e))
        }
    }

    suspend fun rollbackPendingUpdate(distroId: String = "ubuntu"): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val backup = pendingUpdateBackup ?: pathManager.rootfsPreviousDir(distroId).takeIf { it.exists() }
            ?: return@withContext false
        val rootfs = pathManager.rootfsDir(distroId)
        if (rootfs.exists()) {
            val broken = File(rootfs.parentFile, "rootfs.broken")
            SafeFileTree.delete(broken)
            if (!rootfs.renameTo(broken)) {
                throw IllegalStateException("Unable to move the current RootFS (the previous version remains at ${backup.path})")
            }
            if (!backup.renameTo(rootfs)) {
                runCatching { broken.renameTo(rootfs) }
                throw IllegalStateException("Unable to restore the previous RootFS (the previous version remains at ${backup.path})")
            }
            SafeFileTree.delete(broken)
        } else {
            check(backup.renameTo(rootfs)) { "Unable to restore the previous RootFS (the previous version remains at ${backup.path})" }
        }
        pendingUpdateBackup = null
        pendingUpdateVersion = null
        pendingUpdateDigest = null
        pathManager.invalidateDistroSizeCache(distroId)
        pathManager.rootfsUpdatePendingMarker(distroId).delete()
        true
    }

    suspend fun finalizePendingUpdate(distroId: String = "ubuntu") = withContext(NonCancellable + Dispatchers.IO) {
        pendingUpdateVersion?.let {
            markInstalled(distroId, OciRegistryClient.ImageInfo(it, pendingUpdateDigest.orEmpty()))
        }
        pendingUpdateBackup?.takeIf { it.exists() }?.let(SafeFileTree::delete)
        pendingUpdateBackup = null
        pendingUpdateVersion = null
        pendingUpdateDigest = null
        // 更新完成后 distro 体积已变化，作废旧快照，下次展示时重建缓存
        pathManager.invalidateDistroSizeCache(distroId)
        pathManager.rootfsUpdatePendingMarker(distroId).delete()
    }

    private fun prepareStaging(distroId: String): File {
        pathManager.ensureDirectories()
        val staging = pathManager.stagingRootfsDir(distroId)
        SafeFileTree.delete(staging)
        staging.mkdirs()
        return staging
    }

    suspend fun resetDistro(
        distribution: DistributionSpec,
        route: RegistryRoute = RegistryRoute.AUTO,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): AppResult<File> = withContext(Dispatchers.IO) {
        val distroId = distribution.id.lowercase().trim()
        val distroTargetDir = pathManager.rootfsDir(distroId)
        val staging = prepareStaging(distroId)
        recoverInterruptedUpdate(distroId)
        try {
            val image = restoreFromCacheOrPull(distribution, route, staging, onProgress)
            rootfsValidator.validate(staging)
            replaceRootfs(distroId, staging, retainBackup = false)
            markInstalled(distroId, image)
            SafeFileTree.delete(pathManager.homeDir(distroId))
            pathManager.homeDir(distroId).mkdirs()
            pathManager.ensureDistroDirectories(distroId)
            pathManager.cleanupStalePtyMarkers(distroId)
            pathManager.invalidateDistroSizeCache(distroId)
            logger.i("Reset distro ${distribution.displayName} ($distroId) to pristine state at $distroTargetDir")
            AppResult.Success(distroTargetDir)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(pathManager.stagingRootfsDir(distroId))
            logger.e("Failed to reset rootfs for $distroId", throwable)
            failure("Failed to reset sandbox ($distroId)", throwable)
        }
    }

    private suspend fun restoreFromCacheOrPull(
        distribution: DistributionSpec,
        route: RegistryRoute,
        staging: File,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): OciRegistryClient.ImageInfo {
        val distroId = distribution.id.lowercase().trim()
        val layersMarker = pathManager.distroLayersFile(distroId)
        val applyLayer: suspend (File, String) -> Unit = { layer, mediaType ->
            layer.inputStream().use { raw ->
                val stream = when {
                    mediaType.contains("zstd") -> ZstdInputStream(raw)
                    mediaType.contains("gzip") -> GZIPInputStream(raw)
                    mediaType.contains("xz") -> XZInputStream(raw)
                    else -> raw
                }
                stream.use { tarStreamExtractor.extract(it, staging, handleWhiteouts = true) }
            }
        }
        if (layersMarker.isFile) {
            val lines = layersMarker.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val cachedLayers = lines.mapNotNull { line ->
                val parts = line.split(":", limit = 3)
                if (parts.size >= 3) {
                    val type = parts[0]
                    val fileName = parts[1]
                    val mediaType = parts[2]
                    val folder = if (type == "lxc") File(pathManager.cacheDir, "lxc_images") else File(pathManager.cacheDir, "oci_layers")
                    val file = File(folder, fileName)
                    if (file.isFile && file.length() > 0) Pair(file, mediaType) else null
                } else null
            }
            if (cachedLayers.size == lines.size && cachedLayers.isNotEmpty()) {
                logger.i("Found ${cachedLayers.size} cached layers for $distroId, restoring offline in 0-traffic mode")
                val totalBytes = cachedLayers.sumOf { it.first.length() }
                var unpackedBytes = 0L
                cachedLayers.forEachIndexed { index, (file, mediaType) ->
                    applyLayer(file, mediaType)
                    unpackedBytes += file.length()
                    onProgress(DownloadProgress(unpackedBytes, totalBytes))
                    logger.i("Extracted cached layer ${index + 1}/${cachedLayers.size}: ${file.name}")
                }
                val version = pathManager.rootfsVersion(distroId) ?: "oci-cached-${distribution.id}"
                val digest = pathManager.rootfsDigest(distroId) ?: "cached"
                return OciRegistryClient.ImageInfo(version, digest)
            }
        }
        return pullInto(distribution, route, staging, onProgress)
    }

    private suspend fun pullInto(
        distribution: DistributionSpec,
        route: RegistryRoute,
        staging: File,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): OciRegistryClient.ImageInfo {
        val distroId = distribution.id.lowercase().trim()
        val recordedLayers = mutableListOf<String>()
        val applyLayer: suspend (File, String) -> Unit = { layer, mediaType ->
            val type = if (mediaType.contains("lxc")) "lxc" else "oci"
            recordedLayers.add("$type:${layer.name}:$mediaType")
            layer.inputStream().use { raw ->
                val stream = when {
                    mediaType.contains("zstd") -> ZstdInputStream(raw)
                    mediaType.contains("gzip") -> GZIPInputStream(raw)
                    mediaType.contains("xz") -> XZInputStream(raw)
                    else -> raw
                }
                stream.use { tarStreamExtractor.extract(it, staging, handleWhiteouts = true) }
            }
        }
        val info = try {
            ociRegistryClient.pull(
                distribution,
                route,
                File(pathManager.cacheDir, "oci_layers"),
                onProgress,
                resetDestination = {
                    recordedLayers.clear()
                    SafeFileTree.delete(staging)
                    staging.mkdirs()
                },
                applyLayer,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ociFailure: Throwable) {
            if (route == RegistryRoute.OFFICIAL || !lxcImagesClient.supports(distribution.id)) {
                throw ociFailure
            }
            logger.w(
                "OCI routes exhausted for ${distribution.id}, falling back to TUNA lxc-images " +
                    "minimal RootFS (does not include the buildpack-deps toolchain)",
                ociFailure,
            )
            recordedLayers.clear()
            SafeFileTree.delete(staging)
            staging.mkdirs()
            val version = lxcImagesClient.pull(
                distribution,
                File(pathManager.cacheDir, "lxc_images"),
                onProgress,
                applyLayer,
            )
            OciRegistryClient.ImageInfo(version, "lxc-$version")
        }
        if (recordedLayers.isNotEmpty()) {
            runCatching {
                pathManager.metadataDir(distroId).mkdirs()
                pathManager.distroLayersFile(distroId).writeText(recordedLayers.joinToString("\n") + "\n")
            }
        }
        return info
    }

    private fun replaceRootfs(distroId: String, staging: File, retainBackup: Boolean) {
        val rootfs = pathManager.rootfsDir(distroId)
        val backup = pathManager.rootfsPreviousDir(distroId)
        SafeFileTree.delete(backup)
        if (rootfs.exists() && !rootfs.renameTo(backup)) error("Unable to stage the previous RootFS")
        if (!staging.renameTo(rootfs)) {
            if (!backup.renameTo(rootfs)) {
                logger.e("RootFS: failed to activate the new version and failed to restore the previous version (previous version remains at ${backup.path})")
                error("Unable to activate the new RootFS and restore failed (previous version remains at ${backup.path})")
            }
            error("Unable to activate the new RootFS")
        }
        if (retainBackup) pendingUpdateBackup = backup else SafeFileTree.delete(backup)
    }

    private fun preserveUserDirectories(oldRootfs: File, newRootfs: File) {
        listOf("root", "opt/taixu").forEach { relative ->
            val source = File(oldRootfs, relative)
            if (source.exists()) {
                val target = File(newRootfs, relative)
                SafeFileTree.delete(target)
                SafeFileTree.copy(source, target)
            }
        }
    }

    private fun markInstalled(distroId: String, image: OciRegistryClient.ImageInfo) {
        pathManager.metadataDir(distroId).mkdirs()
        pathManager.rootfsInstalledMarker(distroId).writeText(
            "rootfs-version=${image.version}\nrootfs-digest=${image.digest}\n",
        )
        // 新 marker 写入后立刻失效安装列表缓存：
        // 初始化流程在同一事务内就会跑健康检查（依赖 no-arg rootfsDir 解析新发行版）。
        pathManager.invalidateInstalledDistrosCache()
    }

    private fun recoverInterruptedUpdate(distroId: String) {
        val backup = pathManager.rootfsPreviousDir(distroId)
        if (!backup.exists()) {
            pathManager.rootfsUpdatePendingMarker(distroId).delete()
            return
        }
        val rootfs = pathManager.rootfsDir(distroId)
        if (rootfs.exists()) {
            val broken = File(rootfs.parentFile, "rootfs.broken")
            SafeFileTree.delete(broken)
            if (!rootfs.renameTo(broken)) {
                throw IllegalStateException(
                    "Failed to resume interrupted update: unable to move the current RootFS (the previous version is preserved at ${backup.path})",
                )
            }
            runCatching { SafeFileTree.delete(broken) }
        }
        check(backup.renameTo(rootfs)) { "Unable to restore the previous uncommitted RootFS update (the previous version is preserved at ${backup.path})" }
        pathManager.rootfsUpdatePendingMarker(distroId).delete()
        logger.w("Recovered previous RootFS after an interrupted update for $distroId")
    }

    private fun failure(prefix: String, throwable: Throwable): AppResult<File> = AppResult.Failure(
        AppError(ErrorCode.INSTALLATION_FAILED, "$prefix：${throwable.message ?: "Unknown error"}", throwable),
    )
}
