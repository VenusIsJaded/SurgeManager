package com.aliucord.manager.patcher.steps.download

import androidx.compose.runtime.Stable
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import dev.surgecord.manager.R

/**
 * A placeholder step that's always skipped. Used to replace download steps
 * when a local APK is being used instead of downloading from the internet.
 */
@Stable
class SkipDownloadStep(private val reason: String) : Step() {
    override val group = StepGroup.Download
    override val localizedName = R.string.patch_step_skip_download

    override suspend fun execute(container: StepRunner) {
        container.log("Skipping download: $reason")
        state = StepState.Skipped
    }
}
