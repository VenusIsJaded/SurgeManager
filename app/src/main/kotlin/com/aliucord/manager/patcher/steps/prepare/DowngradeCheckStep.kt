package com.aliucord.manager.patcher.steps.prepare

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import com.aliucord.manager.manager.InstallerManager
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import com.aliucord.manager.util.*
import dev.surgecord.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Pre-install validation:
 *  - Detects split-APK installs that can't be directly overwritten by a monolithic APK
 *    and proactively uninstalls (the PackageManager provides no usable error for this case).
 *  - Warns about downgrades but no longer forces an uninstall.
 *    If the install fails due to a version conflict, [InstallStep] will handle
 *    the uninstall-and-retry automatically.
 */
class DowngradeCheckStep(private val options: PatchOptions) : Step(), KoinComponent {
    private val context: Context by inject()
    private val installers: InstallerManager by inject()

    override val group = StepGroup.Prepare
    override val localizedName = R.string.patch_step_downgrade_check

    override suspend fun execute(container: StepRunner) {
        container.log("Fetching version of package ${options.packageName}")
        val packageInfo = try {
            context.packageManager.getPackageInfo(options.packageName, 0)
        } catch (_: NameNotFoundException) {
            state = StepState.Skipped
            container.log("Package not installed, skipping check")
            return
        }

        @Suppress("DEPRECATION")
        val currentVersion = packageInfo.versionCode
        container.log("Version of installed app: $currentVersion")

        // Detect split-APK install: if the package has split APKs, installing a monolithic APK
        // over it will always fail with INSTALL_FAILED_MISSING_SPLIT.
        // This is the ONLY case where we must proactively uninstall, because the PackageManager
        // error for this is unusable (it returns a generic STATUS_FAILURE with no useful message).
        val isSplitInstall = !packageInfo.applicationInfo?.splitSourceDirs.isNullOrEmpty()
        if (isSplitInstall) {
            container.log("Existing install is a split-APK set — must uninstall before installing a monolithic APK")
            mainThread { context.showToast(R.string.installer_uninstall_new) }

            when (val result = installers.getActiveInstaller().waitUninstall(options.packageName)) {
                is InstallerResult.Error -> throw Error("Failed to uninstall split-APK install: ${result.getDebugReason()}")
                is InstallerResult.Cancelled -> throw Error("Existing split-APK install must be removed before continuing")
                else -> {
                    container.log("Successfully uninstalled existing split-APK install")
                    return
                }
            }
        }

        // Version comparison: just log a warning if it looks like a downgrade.
        // The actual install will attempt, and if it fails due to version conflict,
        // InstallStep will automatically prompt for uninstall and retry.
        var targetVersion: Int? = null

        // First, try to get version from local APK step
        val useLocalStep = container.getStepOrNull<UseLocalApkStep>()
        if (useLocalStep != null && useLocalStep.state == StepState.Success) {
            targetVersion = useLocalStep.localVersion
            container.log("Using version from local APK: $targetVersion")
        }

        // Then try FetchInfoStep (legacy)
        if (targetVersion == null) {
            val fetchInfo = container.getStepOrNull<FetchInfoStep>()
            if (fetchInfo != null) {
                targetVersion = fetchInfo.data.discordVersionCode
                container.log("Using version from FetchInfoStep: $targetVersion")
            }
        }

        // Finally try FetchDiscordRNAStep
        if (targetVersion == null) {
            val rnaFetchInfo = container.getStepOrNull<FetchDiscordRNAStep>()
            if (rnaFetchInfo != null) {
                targetVersion = rnaFetchInfo.targetVersion
                container.log("Using version from FetchDiscordRNAStep: $targetVersion")
            }
        }

        if (targetVersion == null) {
            container.log("Could not determine target version, skipping downgrade check")
            state = StepState.Skipped
            return
        }

        if (currentVersion > targetVersion) {
            // Log the downgrade but don't block — let the install try.
            // If it fails, InstallStep will handle the uninstall-and-retry.
            container.log("Current installed version ($currentVersion) is greater than target ($targetVersion). Install may fail and require uninstall.")
        } else {
            container.log("Version check passed (installed: $currentVersion, target: $targetVersion)")
        }
    }
}
