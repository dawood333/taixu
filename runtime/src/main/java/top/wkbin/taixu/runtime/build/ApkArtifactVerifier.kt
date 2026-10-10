package top.wkbin.taixu.runtime.build

import java.io.File
import java.util.zip.ZipFile

data class ApkArtifactVerification(
    val isValid: Boolean,
    val nativeAbis: Set<String>,
    val rejectedEntries: List<String>,
    val message: String,
)

/** Final host-side gate before an APK leaves the workspace. */
object ApkArtifactVerifier {
    private val allowedAbis = setOf("arm64-v8a")

    fun verify(apk: File): ApkArtifactVerification {
        if (!apk.isFile || apk.length() <= 0L) {
            return ApkArtifactVerification(false, emptySet(), emptyList(), "APK file does not exist or is empty")
        }
        return runCatching {
            ZipFile(apk).use { zip ->
                val nativeEntries = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.count { char -> char == '/' } >= 2 }
                    .toList()
                val abis = nativeEntries.mapNotNull { it.split('/').getOrNull(1) }.toSet()
                val rejected = nativeEntries.filter { entry ->
                    val abi = entry.split('/').getOrNull(1)
                    abi != null && abi !in allowedAbis
                }
                if (rejected.isNotEmpty()) {
                    ApkArtifactVerification(
                        isValid = false,
                        nativeAbis = abis,
                        rejectedEntries = rejected.take(20),
                        message = "APK contains non-ARM64 native libraries: ${abis.filterNot { it in allowedAbis }.joinToString()}. TaiXu supports arm64-v8a only.",
                    )
                } else {
                    ApkArtifactVerification(
                        isValid = true,
                        nativeAbis = abis,
                        rejectedEntries = emptyList(),
                        message = if (abis.isEmpty()) "APK contains no native libraries; ABI check passed" else "APK ABI check passed: arm64-v8a",
                    )
                }
            }
        }.getOrElse { error ->
            ApkArtifactVerification(false, emptySet(), emptyList(), "APK ZIP validation failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
