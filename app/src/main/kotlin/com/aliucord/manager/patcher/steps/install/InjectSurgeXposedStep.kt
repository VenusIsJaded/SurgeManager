package com.aliucord.manager.patcher.steps.install

import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import com.aliucord.manager.patcher.steps.download.DownloadSurgeXposedStep
import com.aliucord.manager.patcher.util.Signer
import dev.surgecord.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.patch.LSPatch
import org.lsposed.patch.util.Logger
import java.io.File

class InjectSurgeXposedStep : Step() {
    override val group: StepGroup = StepGroup.Install
    override val localizedName: Int = R.string.patch_step_inject_rain

    /**
     * Standard functional split APK stems that contain executable code and need
     * LSPatch injection. Config splits from APKM archives (e.g. config.arm64_v8a,
     * config.xxhdpi) are pure resource/config and MUST NOT be passed to LSPatch.
     */
    private val STANDARD_FUNCTIONAL_SPLITS = setOf(
        "patched-lang",
        "patched-lib",
        "patched-res",
    )

    suspend fun patch(
        container: StepRunner,
        outputDir: File,
        apkPaths: List<String>,
        embeddedModules: List<String>,
    ) {
        withContext(Dispatchers.IO) {
            LSPatch(
                object : Logger() {
                    override fun d(p0: String?) {
                        container.log("[LSPatch:D] $p0")
                    }

                    override fun e(p0: String?) {
                        container.log("[LSPatch:E] $p0")
                    }

                    override fun i(p0: String?) {
                        container.log("[LSPatch] $p0")
                    }
                },
                *apkPaths.toTypedArray(),
                "-o",
                outputDir.absolutePath,
                "-l",
                "0",
                "-v",
                "-m",
                *embeddedModules.toTypedArray(),
                "-k",
                Signer.getKeystoreFile().absolutePath,
                "password",
                "alias",
                "password"
            ).doCommandLine()
        }
    }

    /**
     * Determines whether an APK should be processed by LSPatch.
     *
     * Config splits from APKM archives (names like "config.arm64_v8a",
     * "split_config.xxhdpi", etc.) are pure resource/config splits that contain
     * no executable code. LSPatch should not inject its component factory stub
     * into them because on Android 13/14, the split APK's classloader lacks
     * LSPosed framework classes, causing NoClassDefFoundError in
     * LSPAppComponentFactoryStub's static initializer at runtime.
     *
     * Standard functional splits (base, lang, lib, res from RNA downloads) and
     * the base APK always need LSPatch processing.
     */
    private fun isLspatchCandidate(apk: File): Boolean {
        val name = apk.nameWithoutExtension

        // The base APK always needs processing
        if (name == "patched") return true

        // Standard functional splits from RNA downloads need processing
        if (name in STANDARD_FUNCTIONAL_SPLITS) return true

        // Config splits from APKM archives — skip LSPatch entirely.
        // These have names like "patched-config.arm64_v8a",
        // "patched-split_config.xxhdpi", etc.
        if (name.contains("config.", ignoreCase = true)) return false

        // Fallback: if the stem does NOT look like a known config split,
        // process it in case it contains code.
        return true
    }

    override suspend fun execute(container: StepRunner) {
        val allApks = container.getStep<CopyDependenciesStep>().patchedApks
        val xposed = container.getStep<DownloadSurgeXposedStep>().targetFile

        // Separate APKs for LSPatch processing vs ones to leave untouched
        val lspatchApks = allApks.filter { isLspatchCandidate(it) }
        val skippedApks = allApks - lspatchApks.toSet()

        container.log("Adding SurgeXposed module with LSPatch")
        container.log("SurgeXposed path = ${xposed.absolutePath}")
        container.log("APKs to patch (${lspatchApks.size}): ${lspatchApks.joinToString { it.name }}")
        if (skippedApks.isNotEmpty()) {
            container.log("Skipping config splits (${skippedApks.size}): ${skippedApks.joinToString { it.name }}")
        }

        // Create temporary folder in working directory
        val tempDir = allApks.first().parentFile!!.resolve("lspatched")

        patch(
            container,
            outputDir = tempDir,
            apkPaths = lspatchApks.map { it.absolutePath },
            embeddedModules = listOf(xposed.absolutePath)
        )

        // Replace only the LSPatch-processed APKs with their patched versions.
        // Config splits that were skipped remain untouched (already signed/aligned).
        lspatchApks.forEach { originalApk ->
            val baseName = originalApk.nameWithoutExtension
            // https://github.com/JingMatrix/LSPatch/blob/b98eaf805018c4cc258ded12efe89212a4855e6a/patch/src/main/java/org/lsposed/patch/LSPatch.java#L159-L163
            // String.format(
            //     Locale.getDefault(), "%s-%d-lspatched.apk",
            //     FilenameUtils.getBaseName(apkFileName),
            //     LSPConfig.instance.VERSION_CODE)
            // )
            val patchedApkName = "${baseName}-${LSPConfig.instance.VERSION_CODE}-lspatched.apk"
            val patchedApk = File(tempDir, patchedApkName)

            if (patchedApk.exists()) {
                patchedApk.copyTo(originalApk, overwrite = true)
                container.log("Replaced ${originalApk.name} with ${patchedApk.name}")
            } else {
                container.log("Warning: Could not find patched APK for ${originalApk.name}")
                container.log("Expected patched APK at: ${patchedApk.absolutePath}")
            }
        }

        tempDir.deleteRecursively()
    }
}
