package com.aliucord.manager.patcher.steps.download

import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import com.aliucord.manager.manager.PathManager
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import com.aliucord.manager.patcher.steps.prepare.UseLocalApkStep
import com.aliucord.manager.patcher.util.InsufficientStorageException
import com.aliucord.manager.patcher.util.extractApkSetFromApkm
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import dev.surgecord.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException

/**
 * Step to duplicate the Discord APKs to be worked on.
 * Supports downloaded APKs, plain local APKs, and local APKM/APK set archives.
 */
class CopyDependenciesStep(private val options: PatchOptions? = null) : Step(), KoinComponent {
    private val paths: PathManager by inject()
    private val application: Application by inject()

    /**
     * The target APK files which can be modified during patching
     */
    val patchedApk: File = paths.patchedApk()
    var patchedLangApk: File? = null
    var patchedLibApk: File? = null
    var patchedResApk: File? = null
    var patchedSplitApks: List<File> = emptyList()
        private set

    val patchedApks
        get() = listOfNotNull(
            patchedApk,
            patchedLangApk.takeIf { it?.exists() == true },
            patchedLibApk.takeIf { it?.exists() == true },
            patchedResApk.takeIf { it?.exists() == true }
        ) + patchedSplitApks.filter { it.exists() }

    override val group = StepGroup.Download
    override val localizedName = R.string.patch_step_copy_deps

    override suspend fun execute(container: StepRunner) {
        // Try to get the source APK from various steps
        val srcApk = getSourceApk(container)
            ?: throw Error("No source APK found! Make sure either a local APK is provided or downloads are available.")

        val langApk = container.getStepOrNull<DownloadDiscordRNALangStep>()?.targetFile
        val libApk = container.getStepOrNull<DownloadDiscordRNALibStep>()?.targetFile
        val resApk = container.getStepOrNull<DownloadDiscordRNAResourcesStep>()?.targetFile

        val dir = paths.patchingWorkingDir()

        container.log("Clearing patched directory")
        if (!dir.deleteRecursively())
            throw Error("Failed to clear existing patched dir")
        if (!dir.mkdirs() && !dir.exists())
            throw Error("Failed to create patched dir")

        // Preallocate space for file copy and future patching operations
        if (Build.VERSION.SDK_INT >= 26) {
            val storageManager = application.getSystemService<StorageManager>()!!
            val targetFileStorageId = storageManager.getUuidForPath(patchedApk)
            val fileSize = srcApk.length() + (langApk?.length() ?: 0) + (libApk?.length() ?: 0) + (resApk?.length() ?: 0)

            // We request 3.5x the size of the APK, to give space for the following:
            // 1) A copy of the APK
            // 2) Modifying the copied APK (whether this is necessary I'm not sure)
            // 3) Extracting native libs and other various operations
            val allocSize = (fileSize * 3.5).toLong()

            try {
                storageManager.allocateBytes(targetFileStorageId, allocSize)
            } catch (e: IOException) {
                throw InsufficientStorageException(e.message)
            }
        }

        if (isUsingLocalApk(container)) {
            if (copyLocalApkSetIfPresent(srcApk, dir, container)) return
        }

        fun copyApkSafely(src: File?, part: String, onCopied: (File) -> Unit) {
            src?.let {
                container.log("Copying patched apk from ${it.absolutePath} to $part")
                val destFile = File(dir, paths.patchedApk(part).name)
                it.copyTo(destFile, overwrite = true)
                onCopied(destFile)
            }
        }

        // Base APK
        container.log("Copying base apk from ${srcApk.absolutePath} to ${patchedApk.absolutePath}")
        srcApk.copyTo(patchedApk, overwrite = true)

        // Optional APKs (only present in RNA split APK installs)
        copyApkSafely(langApk, "lang") { patchedLangApk = it }
        copyApkSafely(libApk, "lib") { patchedLibApk = it }
        copyApkSafely(resApk, "res") { patchedResApk = it }
    }

    private fun copyLocalApkSetIfPresent(srcApk: File, dir: File, container: StepRunner): Boolean {
        val extractedDir = File(dir, "local-apk-set")
        val apkSet = extractApkSetFromApkm(srcApk, extractedDir) ?: return false

        val compatibleSplits = apkSet.splitApks.filter { split ->
            val splitAbi = abiForSplit(split)
            val shouldKeep = splitAbi == null || splitAbi in supportedSplitAbis()
            if (!shouldKeep) {
                container.log("Skipping unsupported ABI split ${split.name} (device ABIs: ${Build.SUPPORTED_ABIS.joinToString()})")
            }
            shouldKeep
        }

        container.log(
            "Local APK is an APKM/APK set; extracted base plus ${apkSet.splitApks.size} split APK(s), " +
                "keeping ${compatibleSplits.size} compatible split APK(s)"
        )
        container.log("Copying base apk from ${apkSet.baseApk.absolutePath} to ${patchedApk.absolutePath}")
        apkSet.baseApk.copyTo(patchedApk, overwrite = true)

        patchedSplitApks = compatibleSplits.mapIndexed { index, split ->
            val splitName = split.nameWithoutExtension
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "split_$index" }
            val destFile = File(dir, "patched-$splitName.apk")
            container.log("Copying split apk from ${split.absolutePath} to ${destFile.absolutePath}")
            split.copyTo(destFile, overwrite = true)
        }

        extractedDir.deleteRecursively()
        return true
    }

    /**
     * APKM archives may contain config splits for every CPU architecture. Installing
     * the x86/armeabi-v7a splits on an arm64-only device can make the resulting app
     * unlaunchable or fail ABI checks. Keep only the split matching this device.
     */
    private fun abiForSplit(split: File): String? {
        val name = split.nameWithoutExtension
        return KNOWN_SPLIT_ABIS.firstOrNull { abi ->
            name == "config.$abi" ||
                name == "split_config.$abi" ||
                name.endsWith("_split_config.$abi") ||
                name.endsWith(".config.$abi")
        }
    }

    private fun supportedSplitAbis(): Set<String> = Build.SUPPORTED_ABIS
        .map { it.replace('-', '_') }
        .toSet()

    /**
     * Find the source APK from available steps.
     * Priority: Local APK > DownloadDiscordRNABaseStep > DownloadDiscordStep
     */
    private fun getSourceApk(container: StepRunner): File? {
        // Try local APK first
        val useLocalStep = container.getStepOrNull<UseLocalApkStep>()
        if (useLocalStep != null && useLocalStep.state == StepState.Success) {
            val localApkPath = options?.localApkPath
            if (!localApkPath.isNullOrBlank()) {
                val localFile = File(localApkPath)
                if (localFile.exists()) {
                    container.log("Using local APK as source: ${localFile.absolutePath}")
                    return localFile
                }
            }
        }

        // Try RNA base APK download
        val rnaBaseStep = container.getStepOrNull<DownloadDiscordRNABaseStep>()
        if (rnaBaseStep != null) {
            return rnaBaseStep.targetFile
        }

        // Try legacy Discord download
        val discordStep = container.getStepOrNull<DownloadDiscordStep>()
        if (discordStep != null) {
            return discordStep.targetFile
        }

        return null
    }

    private fun isUsingLocalApk(container: StepRunner): Boolean {
        val useLocalStep = container.getStepOrNull<UseLocalApkStep>()
        return useLocalStep != null && useLocalStep.state == StepState.Success && !options?.localApkPath.isNullOrBlank()
    }

    private companion object {
        val KNOWN_SPLIT_ABIS = setOf("armeabi_v7a", "arm64_v8a", "x86", "x86_64")
    }
}
