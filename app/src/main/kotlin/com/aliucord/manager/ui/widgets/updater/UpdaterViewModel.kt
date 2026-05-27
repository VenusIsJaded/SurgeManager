package com.aliucord.manager.ui.widgets.updater

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliucord.manager.installers.InstallerResult
import com.aliucord.manager.manager.InstallerManager
import com.aliucord.manager.manager.InstallerSetting
import com.aliucord.manager.manager.download.IDownloadManager
import com.aliucord.manager.manager.download.KtorDownloadManager
import com.aliucord.manager.network.models.GithubRelease
import com.aliucord.manager.network.services.SurgeGitHubService
import com.aliucord.manager.network.utils.SemVer
import com.aliucord.manager.network.utils.getOrThrow
import com.aliucord.manager.util.*
import dev.surgecord.manager.BuildConfig
import dev.surgecord.manager.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.system.exitProcess

class UpdaterViewModel(
    private val github: SurgeGitHubService,
    private val downloader: KtorDownloadManager,
    private val installers: InstallerManager,
    private val application: Application,
) : ViewModel() {
    var showDialog by mutableStateOf(false)
        private set
    var targetVersion by mutableStateOf<String?>(null)
        private set
    val downloadProgress: StateFlow<Float?>
        private field = MutableStateFlow(null)
    val isWorking: StateFlow<Boolean>
        private field = MutableStateFlow(false)

    private var targetApkUrl: String? = null

    init {
        viewModelScope.launchIO {
            try {
                fetchInfo()
            } catch (t: Throwable) {
                Log.e(BuildConfig.TAG, "Failed to check for updates!", t)
                mainThread { application.showToast(R.string.updater_check_fail) }
            }
        }
    }

    fun dismissDialog() {
        showDialog = false
    }

    fun triggerUpdate() = viewModelScope.launchIO {
        if (!isWorking.compareAndSet(expect = false, update = true))
            return@launchIO

        downloadProgress.value = null
        val apkFile = application.cacheDir.resolve("manager.apk")

        try {
            val url = targetApkUrl ?: throw IllegalStateException("No update APK URL is available")

            apkFile.apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }

            val downloadResult = downloader.download(
                url = url,
                out = apkFile,
                onProgressUpdate = { downloadProgress.value = it },
            )

            when (downloadResult) {
                is IDownloadManager.Result.Success ->
                    Log.d(BuildConfig.TAG, "Downloaded update")

                is IDownloadManager.Result.Cancelled -> {
                    Log.i(BuildConfig.TAG, "Update cancelled")
                    return@launchIO
                }

                is IDownloadManager.Result.Error ->
                    throw IllegalStateException("Failed to download update: ${downloadResult.getDebugReason()}", downloadResult.getError())
            }

            downloadProgress.value = null

            val installer = installers.getInstaller(InstallerSetting.PM)
            val installResult = installer.waitInstall(
                apks = listOf(apkFile),
                silent = true,
            )

            when (installResult) {
                InstallerResult.Success -> {
                    Log.w(BuildConfig.TAG, "Update completed without restarting app!")
                    exitProcess(1)
                }

                is InstallerResult.Cancelled ->
                    Log.i(BuildConfig.TAG, "Update cancelled")

                is InstallerResult.Error ->
                    throw Exception("Failed to install update: ${installResult.getDebugReason()}")
            }
        } catch (t: Throwable) {
            Log.e(BuildConfig.TAG, "Failed to perform update!", t)

            mainThread {
                application.showToast(R.string.updater_update_fail)
                launchReleasesPage()
            }
        } finally {
            isWorking.value = false
            downloadProgress.value = null

            try {
                if (apkFile.exists()) apkFile.delete()
            } catch (t: Throwable) {
                Log.w(BuildConfig.TAG, "Failed to clean up installed update!", t)
            }
        }
    }

    /**
     * Fetch release data from GitHub and populate the update dialog if a newer Surge Manager APK exists.
     */
    private suspend fun fetchInfo() {
        Log.d(BuildConfig.TAG, "Checking for updates...")

        val currentVersion = SemVer.parseOrNull(BuildConfig.VERSION_NAME)
            ?: throw Error("Failed to parse current app version")

        val releases = github.getManagerReleases().getOrThrow()

        val (version, apkUrl) = releases
            .mapNotNull { release ->
                val version = SemVer.parseOrNull(release.tagName)
                    ?: return@mapNotNull null
                val asset = release.findManagerApkAsset()
                    ?: return@mapNotNull null

                version to asset.browserDownloadUrl
            }
            .maxByOrNull { it.first }
            ?: return

        if (currentVersion >= version) {
            Log.d(BuildConfig.TAG, "Already updated to latest version!")
            return
        }

        Log.d(BuildConfig.TAG, "Found an update! version=$version url=$apkUrl")
        mainThread {
            targetVersion = version.toString()
            targetApkUrl = apkUrl
            showDialog = true
        }
    }

    private fun GithubRelease.findManagerApkAsset() = assets.find { it.name == "app-release.apk" }
        ?: assets.find { asset ->
            val name = asset.name.lowercase()
            name.endsWith(".apk") && "manager" in name
        }
        ?: assets.find { it.name.endsWith(".apk", ignoreCase = true) }

    private fun launchReleasesPage() {
        try {
            Intent(Intent.ACTION_VIEW, SurgeGitHubService.LATEST_MANAGER_RELEASE_HTML_URL.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .also(application::startActivity)
        } catch (t: Throwable) {
            Log.w(BuildConfig.TAG, "Failed to open latest Github release in browser!", t)
        }
    }
}
