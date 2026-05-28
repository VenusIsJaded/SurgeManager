package com.aliucord.manager.patcher.steps.download

import com.aliucord.manager.manager.PathManager
import com.aliucord.manager.network.services.SurgeGitHubService
import com.aliucord.manager.network.utils.SemVer
import com.aliucord.manager.network.utils.getOrThrow
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.base.DownloadStep
import dev.surgecord.manager.R
import org.koin.core.component.inject

class DownloadSurgeXposedStep : DownloadStep() {
    private val paths: PathManager by inject()
    private val surgeGitHubService: SurgeGitHubService by inject()

    override val localizedName = R.string.patch_step_dl_surgexposed
    override val targetFile get() = paths.cachedSurgeXposed(targetVersion)
    override lateinit var targetUrl: String

    lateinit var targetVersion: SemVer
        private set

    override suspend fun execute(container: StepRunner) {
        val releases = surgeGitHubService.getXposedReleases().getOrThrow()
        val latest = releases
            .mapNotNull { release ->
                val version = SemVer.parseOrNull(release.name.ifBlank { release.tagName })
                    ?: return@mapNotNull null
                val asset = release.assets.firstOrNull { it.name == "app-release.apk" }
                    ?: return@mapNotNull null

                Triple(version, release, asset)
            }
            .maxByOrNull { it.first }
            ?: throw Error("No valid SurgeXposed release with app-release.apk was found")

        targetVersion = latest.first
        targetUrl = latest.third.browserDownloadUrl
        container.log("Latest SurgeXposed release is ${latest.second.name.ifBlank { latest.second.tagName }}")
        super.execute(container)
    }
}
