package com.aliucord.manager.patcher.steps.prepare

import androidx.compose.runtime.Stable
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import dev.surgecord.manager.R

/**
 * A placeholder step that's always skipped when using a local APK.
 * Replaces RestoreDownloadsStep in local APK mode.
 */
@Stable
class SkipRestoreDownloadsStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.patch_step_restore_cache

    override suspend fun execute(container: StepRunner) {
        container.log("Skipping cache restore (local APK mode)")
        state = StepState.Skipped
    }
}
