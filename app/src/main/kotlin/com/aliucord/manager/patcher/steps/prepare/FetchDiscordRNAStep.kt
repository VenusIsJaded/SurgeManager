package com.aliucord.manager.patcher.steps.prepare

import com.aliucord.manager.network.services.RNATrackerService
import com.aliucord.manager.network.services.SurgeGitHubService
import com.aliucord.manager.network.utils.getOrThrow
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import dev.surgecord.manager.R
import org.koin.core.component.KoinComponent
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import kotlin.properties.Delegates

class FetchDiscordRNAStep(val options: PatchOptions) : Step(), KoinComponent {
    private val rnaTrackerService: RNATrackerService by inject()
    private val surgeGitHubService: SurgeGitHubService by inject()

    override val group: StepGroup = StepGroup.Prepare
    override val localizedName: Int = R.string.patch_step_fetch_rna

    var targetVersion by Delegates.notNull<Int>()
        private set

    override suspend fun execute(container: StepRunner) {
        container.log("Starting Discord version resolution...")

        val controlRepoVersion = fetchControlRepoVersion(container)
        val developerOverride = options.customVersionCode
            .takeIf { options.isDevMode && it.isNotBlank() }
            ?.toIntOrNull()

        targetVersion = when {
            controlRepoVersion != null -> {
                container.log("Successfully fetched default version from ControlRepo: $controlRepoVersion")
                controlRepoVersion
            }

            developerOverride != null -> {
                container.log("Using manual developer override version (Dev Mode ON): $developerOverride")
                developerOverride
            }

            else -> {
                if (options.isDevMode && options.customVersionCode.isNotBlank()) {
                    container.log("Ignoring invalid developer override version: ${options.customVersionCode}")
                }

                container.log("No ControlRepo version or valid developer override. Falling back to RNATracker Stable...")
                rnaTrackerService.getLatestDiscordVersions().getOrThrow().latest.stable
            }
        }

        container.log("Selected Discord version: $targetVersion")
    }

    private suspend fun fetchControlRepoVersion(container: StepRunner): Int? {
        return try {
            surgeGitHubService
                .getControlRepo()
                .getOrThrow()
                .firstOrNull()
                ?.discord
                ?.takeIf { it > 0 }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            container.log("ControlRepo fetch failed: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }
}
