package com.aliucord.manager.network.services

import com.aliucord.manager.network.models.GithubRelease
import com.aliucord.manager.network.utils.ApiResponse
import com.aliucord.manager.network.models.ControlRepoEntry
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders

class SurgeGitHubService(
    private val http: HttpService,
) {
    suspend fun getManagerReleases(): ApiResponse<List<GithubRelease>> {
        return http.request {
            url("https://api.github.com/repos/$ORG/$MANAGER_REPO/releases")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    suspend fun getLatestXposedRelease(): ApiResponse<GithubRelease> {
        return http.request {
            url("https://api.github.com/repos/$ORG/$XPOSED_REPO/releases/latest")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    suspend fun getControlRepo(): ApiResponse<List<ControlRepoEntry>> {
        return http.request {
            url("https://raw.githubusercontent.com/$ORG/$CONTROL_REPO/main/control.json")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    companion object {
        const val ORG = "VenusIsJaded"
        const val MANAGER_REPO = "SurgeManager"
        const val XPOSED_REPO = "SurgeXposed"
        const val CONTROL_REPO = "ControlRepo"
        const val LATEST_MANAGER_RELEASE_HTML_URL = "https://github.com/$ORG/$MANAGER_REPO/releases/latest"
    }
}
