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

    override suspend fun execute(container: StepRunner) {
        val copyStep = container.getStep<CopyDependenciesStep>()
        val allApks = copyStep.patchedApks
        val xposed = container.getStep<DownloadSurgeXposedStep>().targetFile

        container.log("Adding SurgeXposed module with LSPatch")
        container.log("SurgeXposed path = ${xposed.absolutePath}")

        // IMPORTANT: Only pass the BASE APK to LSPatch, not split APKs.
        //
        // Split APKs (lang, lib, res, config.*) don't need LSPatch processing and
        // attempting to patch them causes NoClassDefFoundError in LSPAppComponentFactoryStub
        // at runtime on many devices. The stub in each split APK depends on LSPosed framework
        // classes that only exist in the base APK's classloader context. When Android loads
        // a split APK, its classloader doesn't have access to those classes, causing the crash.
        //
        // The SurgeXposed module is embedded in the base APK, and all Xposed hooks operate
        // through the base APK's process. Split APKs are just resources/native libs and
        // don't need LSPatch processing.
        val baseApk = copyStep.patchedApk
        val splitApks = allApks.filter { it != baseApk }

        container.log("Processing base APK only: ${baseApk.name}")
        if (splitApks.isNotEmpty()) {
            container.log("Skipping ${splitApks.size} split APK(s): ${splitApks.joinToString { it.name }}")
        }

        // Create temporary folder in working directory
        val tempDir = baseApk.parentFile!!.resolve("lspatched")

        // Only patch the base APK through LSPatch
        patch(
            container,
            outputDir = tempDir,
            apkPaths = listOf(baseApk.absolutePath),
            embeddedModules = listOf(xposed.absolutePath)
        )

        // Replace only the base APK with the patched version.
        // Split APKs are left untouched (already aligned and signed).
        val baseName = baseApk.nameWithoutExtension
        // https://github.com/JingMatrix/LSPatch/blob/b98eaf805018c4cc258ded12efe89212a4855e6a/patch/src/main/java/org/lsposed/patch/LSPatch.java#L159-L163
        // String.format(
        //     Locale.getDefault(), "%s-%d-lspatched.apk",
        //     FilenameUtils.getBaseName(apkFileName),
        //     LSPConfig.instance.VERSION_CODE)
        // )
        val patchedApkName = "${baseName}-${LSPConfig.instance.VERSION_CODE}-lspatched.apk"
        val patchedApk = File(tempDir, patchedApkName)

        if (patchedApk.exists()) {
            patchedApk.copyTo(baseApk, overwrite = true)
            container.log("Replaced ${baseApk.name} with ${patchedApk.name}")
        } else {
            throw Error("Failed to find patched base APK at: ${patchedApk.absolutePath}")
        }

        tempDir.deleteRecursively()
    }
}