package com.aliucord.manager.patcher

import com.aliucord.manager.patcher.steps.download.*
import com.aliucord.manager.patcher.steps.install.*
import com.aliucord.manager.patcher.steps.patch.*
import com.aliucord.manager.patcher.steps.prepare.*
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import kotlinx.collections.immutable.persistentListOf

class SurgePatchRunner(options: PatchOptions) : StepRunner() {
    private val isLocalApk = !options.localApkPath.isNullOrBlank()

    override val steps = if (isLocalApk) {
        persistentListOf(
            UseLocalApkStep(options),
            DowngradeCheckStep(options),
            SkipRestoreDownloadsStep(),

            SkipDownloadStep("base APK (using local file)"),
            SkipDownloadStep("lang APK (using local file)"),
            SkipDownloadStep("resources APK (using local file)"),
            SkipDownloadStep("libs APK (using local file)"),
            DownloadSurgeXposedStep(),

            CopyDependenciesStep(options),

            NormalizeLocalZipStep(),

            PatchIconsStep(options),
            PatchSurgeManifestStep(options),
            SaveMetadataStep(options),

            SigningStep(),
            InjectSurgeXposedStep(),

            InstallStep(options),
            CleanupStep(),
        )
    } else {
        persistentListOf(
            FetchDiscordRNAStep(options),
            DowngradeCheckStep(options),
            RestoreDownloadsStep(),

            DownloadDiscordRNABaseStep(),
            DownloadDiscordRNALangStep(),
            DownloadDiscordRNAResourcesStep(),
            DownloadDiscordRNALibStep(),
            DownloadSurgeXposedStep(),

            CopyDependenciesStep(options),

            PatchIconsStep(options),
            PatchSurgeManifestStep(options),
            SaveMetadataStep(options),

            AlignmentStep(),
            SigningStep(),
            InjectSurgeXposedStep(),

            InstallStep(options),
            CleanupStep(),
        )
    }
}
