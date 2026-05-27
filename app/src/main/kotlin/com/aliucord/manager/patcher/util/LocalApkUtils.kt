package com.aliucord.manager.patcher.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Data class holding info extracted from an APK file.
 */
data class ApkFileInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
)

/**
 * A local APK source after expanding an APKM/APK set archive.
 */
data class LocalApkSet(
    val baseApk: File,
    val splitApks: List<File>,
)

/**
 * Extracts package info from a plain APK or from the base APK inside an APKM/APK set archive.
 */
fun extractApkFileInfo(context: Context, apkFile: File): ApkFileInfo? {
    val directInfo = getPackageArchiveInfoCompat(context.packageManager, apkFile.absolutePath)
    val info = directInfo ?: run {
        val base = extractBaseApkFromApkm(context, apkFile) ?: return null
        try {
            getPackageArchiveInfoCompat(context.packageManager, base.absolutePath)
        } finally {
            base.parentFile?.deleteRecursively()
        }
    }

    return info?.let {
        ApkFileInfo(
            versionName = it.versionName ?: "unknown",
            versionCode = it.longVersionCode,
            packageName = it.packageName,
        )
    }
}

@Suppress("DEPRECATION")
private fun getPackageArchiveInfoCompat(pm: PackageManager, path: String): PackageInfo? {
    return try {
        pm.getPackageArchiveInfo(
            path,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Returns true when [file] is an APKM/APK set archive containing APK entries rather than
 * a single installable APK. Plain APK files are also ZIPs, so we only treat the file as
 * an APK set when it contains nested APK entries and does not have a root manifest.
 */
fun isApkSetArchive(file: File): Boolean {
    return try {
        ZipFile(file).use { zip ->
            val hasRootManifest = zip.getEntry("AndroidManifest.xml") != null
            val hasNestedApks = zip.entries().asSequence().any { it.isApkEntry }
            hasNestedApks && !hasRootManifest
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Extracts all APK files from an APKM/APK set archive into [outputDir].
 *
 * @return The extracted base APK and all split APKs, or null when [apkmFile] is not an APK set
 *         archive or extraction failed.
 */
fun extractApkSetFromApkm(apkmFile: File, outputDir: File): LocalApkSet? {
    return try {
        ZipFile(apkmFile).use { zip ->
            val hasRootManifest = zip.getEntry("AndroidManifest.xml") != null
            val apkEntries = zip.entries().asSequence()
                .filter { it.isApkEntry }
                .toList()

            if (hasRootManifest || apkEntries.isEmpty()) return null

            val baseEntry = findBaseApkEntry(apkEntries)
                ?: return null

            outputDir.deleteRecursively()
            if (!outputDir.mkdirs() && !outputDir.exists()) return null

            val extracted = apkEntries.mapIndexed { index, entry ->
                val fileName = buildExtractedApkName(entry, index, entry == baseEntry)
                val outFile = outputDir.resolve(fileName)
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                entry to outFile
            }

            val baseApk = extracted.first { it.first == baseEntry }.second
            val splitApks = extracted
                .filterNot { it.first == baseEntry }
                .map { it.second }

            LocalApkSet(baseApk, splitApks)
        }
    } catch (_: Exception) {
        outputDir.deleteRecursively()
        null
    }
}

/**
 * Extracts the base.apk from an APKM (split APK bundle) file.
 * APKM files are ZIP archives containing multiple APK files.
 *
 * @return The extracted base APK file, or null if not found or extraction failed.
 */
fun extractBaseApkFromApkm(context: Context, apkmFile: File): File? {
    val extractedDir = File(context.cacheDir, "apkm_extracted_${System.currentTimeMillis()}")
    if (!extractedDir.mkdirs() && !extractedDir.exists()) return null

    val apkSet = extractApkSetFromApkm(apkmFile, extractedDir)
    if (apkSet != null) {
        // Metadata callers only need the base; remove split APKs immediately.
        apkSet.splitApks.forEach { it.delete() }
        return apkSet.baseApk
    }

    extractedDir.deleteRecursively()
    return null
}

private val ZipEntry.isApkEntry: Boolean
    get() = !isDirectory && name.endsWith(".apk", ignoreCase = true)

private fun findBaseApkEntry(entries: List<ZipEntry>): ZipEntry? {
    return entries.find { entry ->
        val name = entry.name.lowercase()
        name == "base.apk" || name.endsWith("/base.apk")
    } ?: entries.maxByOrNull { it.size.coerceAtLeast(0L) }
}

private fun buildExtractedApkName(entry: ZipEntry, index: Int, isBase: Boolean): String {
    if (isBase) return "base.apk"

    val rawName = entry.name.substringAfterLast('/').substringAfterLast('\\')
    val safeName = rawName
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "split_$index.apk" }
        .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }

    return "%03d_%s".format(index, safeName)
}
