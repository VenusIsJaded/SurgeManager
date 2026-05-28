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

            PatchIconsStep(options),
            PatchSurgeManifestStep(options),
            SaveMetadataStep(options),

            // Keep the official Surge Manager pipeline order. Local APK support should
            // only change where the APKs come from; LSPatch remains the final
            // APK-producing step to avoid launch crashes.
            AlignmentStep(),
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

            // Auto-downloads intentionally use the same CopyDependenciesStep() call
            // and install order as official Surge Manager. Local APK support is isolated
            // to the branch above so the known-good auto path is not changed.
            CopyDependenciesStep(),

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
