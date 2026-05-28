package com.aliucord.manager.patcher.steps.prepare

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
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
 *  - Logs a warning about downgrades but does NOT force an uninstall.
 *    If the install fails due to a version conflict, [InstallStep] will handle
 *    the uninstall-and-retry automatically.
 *
 * Note: The split-APK check was removed because SurgeManager always installs
 * split APKs (base + lang + resources + lib). Updating split-over-split works
 * fine — Android only errors on split→monolithic or monolithic→split mismatches,
 * neither of which applies here.
 */
class DowngradeCheckStep(private val options: PatchOptions) : Step(), KoinComponent {
    private val context: Context by inject()

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
