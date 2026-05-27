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
        val latestRelease = surgeGitHubService.getXposedReleases()
            .getOrThrow()
            .firstOrNull { release -> release.assets.any { it.name == "app-release.apk" } }
            ?: throw Error("No SurgeXposed release with app-release.apk was found")
        val releaseVersion = latestRelease.name.ifBlank { latestRelease.tagName }
        container.log("Latest SurgeXposed release is $releaseVersion")

        targetVersion = SemVer.parse(releaseVersion)
        targetUrl = latestRelease.assets.first { it.name == "app-release.apk" }.browserDownloadUrl
        super.execute(container)
    }
}
