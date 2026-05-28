package com.aliucord.manager.patcher.steps.install

import android.content.Context
import java.io.File
import android.content.pm.PackageInstaller
import androidx.lifecycle.*
import com.aliucord.manager.installers.InstallerResult
import com.aliucord.manager.installers.pm.PMInstallerError
import com.aliucord.manager.manager.*
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import com.aliucord.manager.ui.components.dialogs.PlayProtectDialog
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import com.aliucord.manager.ui.util.InstallNotifications
import com.aliucord.manager.util.isPackageInstalled
import com.aliucord.manager.util.*
import com.aliucord.manager.util.isPlayProtectEnabled
import dev.surgecord.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ID used for showing ready notifications if the activity is currently minimized when having reached this step.
 */
private const val READY_NOTIF_ID = 200001

/**
 * Install the final APK with the system's PackageManager.
 *
 * If the installation fails due to a version conflict or split-APK mismatch
 * (common when updating an existing SurgeCord install), the user will be prompted
 * to uninstall the existing version and the installation will be retried automatically.
 * This avoids requiring a manual uninstall before every update.
 */
open class InstallStep(private val options: PatchOptions) : Step(), KoinComponent {
    private val context: Context by inject()
    private val installers: InstallerManager by inject()
    private val prefs: PreferencesManager by inject()
    private val overlays: OverlayManager by inject()

    override val group = StepGroup.Install
    override val localizedName = R.string.patch_step_install

    override suspend fun execute(container: StepRunner) {
        val apks = container.getStep<CopyDependenciesStep>().patchedApks

        // If app backgrounded, show notification
        if (ProcessLifecycleOwner.get().lifecycle.currentState == Lifecycle.State.CREATED) {
            InstallNotifications.createNotification(
                context = context,
                id = READY_NOTIF_ID,
                title = R.string.notif_install_ready_title,
                description = R.string.notif_install_ready_desc,
            )

            container.log("Waiting until manager is resumed to continue installation")
        }

        // Wait until app resumed
        ProcessLifecycleOwner.get().lifecycle.withResumed {}

        // Show [PlayProtectDialog] and wait until it gets dismissed
        if (prefs.showPlayProtectWarning
            && !prefs.devMode
            && !context.isPackageInstalled(options.packageName)
            && context.isPlayProtectEnabled() == true
        ) {
            container.log("Showing play protect warning dialog")
            val neverShowAgain = overlays.startComposableForResult { onResult ->
                PlayProtectDialog(onDismiss = onResult)
            }
            prefs.showPlayProtectWarning = !neverShowAgain
        }

        // --- First install attempt ---
        container.log("Installing ${apks.joinToString(", ") { it.name }}, silent: ${!prefs.devMode}")
        val result = installers.getActiveInstaller().waitInstall(
            apks = apks,
            silent = !prefs.devMode,
        )

        when (result) {
            is InstallerResult.Error -> {
                val isRetryable = isRetryableError(result)

                if (isRetryable && context.isPackageInstalled(options.packageName)) {
                    // Install failed with a retryable error and the target package is still installed.
                    // Prompt the user to uninstall, then retry the install automatically.
                    container.log("Install failed with retryable error: ${result.getDebugReason()}")

                    if (shouldAutoUninstall(container)) {
                        container.log("Uninstalling existing installation to retry...")
                        performUninstallAndRetry(container, apks)
                    } else {
                        throw Error("Failed to install APKs: ${result.getDebugReason()}")
                    }
                } else {
                    container.log("Installation failed")
                    throw Error("Failed to install APKs: ${result.getDebugReason()}")
                }
            }

            is InstallerResult.Cancelled -> {
                // The install screen is automatically closed immediately once cleanup finishes
                state = StepState.Skipped
                container.log("Installation was cancelled by user")
            }

            InstallerResult.Success ->
                container.log("Installation successful")
        }
    }

    /**
     * Checks if the install error is one that can be resolved by uninstalling first.
     */
    private fun isRetryableError(result: InstallerResult.Error): Boolean {
        if (result !is PMInstallerError) return false
        return result.status in RETRYABLE_STATUS_CODES
    }

    /**
     * Asks the user if they want to uninstall the existing installation.
     * Returns true if the uninstall should proceed.
     */
    private suspend fun shouldAutoUninstall(container: StepRunner): Boolean {
        container.log("Asking user to uninstall existing installation")
        mainThread { context.showToast(R.string.installer_uninstall_new) }

        return true
    }

    /**
     * Uninstalls the existing package and retries the installation.
     */
    private suspend fun performUninstallAndRetry(container: StepRunner, apks: List<File>) {
        when (val uninstallResult = installers.getActiveInstaller().waitUninstall(options.packageName)) {
            is InstallerResult.Error -> throw Error("Failed to uninstall: ${uninstallResult.getDebugReason()}")
            is InstallerResult.Cancelled -> throw Error("Existing installation must be removed before continuing")
            else -> {
                container.log("Successfully uninstalled existing installation, retrying install...")
            }
        }

        // Retry the installation after successful uninstall
        container.log("Retrying installation of ${apks.joinToString(", ") { it.name }}")
        val retryResult = installers.getActiveInstaller().waitInstall(
            apks = apks,
            silent = !prefs.devMode,
        )

        when (retryResult) {
            is InstallerResult.Error -> {
                container.log("Retry installation failed")
                throw Error("Failed to install APKs after uninstall: ${retryResult.getDebugReason()}")
            }

            is InstallerResult.Cancelled -> {
                state = StepState.Skipped
                container.log("Retry installation was cancelled by user")
            }

            InstallerResult.Success ->
                container.log("Retry installation successful")
        }
    }

    companion object {
        /**
         * PackageInstaller status codes that indicate the install might succeed
         * after uninstalling the existing package first.
         *
         * - STATUS_FAILURE_CONFLICT (4): Version conflict or signature mismatch
         * - STATUS_FAILURE_INVALID (3): e.g. INSTALL_FAILED_MISSING_SPLIT
         * - STATUS_FAILURE (1): Generic failure that sometimes resolves after uninstall
         * - STATUS_FAILURE_INCOMPATIBLE (6): Device/version incompatibility
         */
        private val RETRYABLE_STATUS_CODES: Set<Int> = setOf(
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        )
    }
}
