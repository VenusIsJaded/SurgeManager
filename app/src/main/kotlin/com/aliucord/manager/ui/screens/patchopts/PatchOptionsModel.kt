package com.aliucord.manager.ui.screens.patchopts

import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import androidx.compose.runtime.*
import androidx.core.content.getSystemService
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.aliucord.manager.manager.PreferencesManager
import com.aliucord.manager.patcher.util.extractApkFileInfo
import com.aliucord.manager.ui.util.DiscordVersion
import com.aliucord.manager.util.*
import java.io.File

class PatchOptionsModel(
    prefilledOptions: PatchOptions,
    private val context: Context,
    private val prefs: PreferencesManager,
) : ScreenModel {
    // ---------- Local APK path (preserved from prefilled options) ----------
    val localApkPath: String? = prefilledOptions.localApkPath.takeIf { !it.isNullOrBlank() }

    // ---------- Local APK info (version, name) ----------
    /**
     * Version string extracted from the local APK file (e.g. "v159.14 (123456)"),
     * or null if no local APK is selected or the APK couldn't be parsed.
     */
    val localApkVersionInfo: String? = run {
        val path = localApkPath
        if (path == null) return@run null
        val file = File(path)
        if (!file.exists()) return@run null

        val info = extractApkFileInfo(context, file)

        if (info != null) {
            "v${info.versionName} (${info.versionCode})"
        } else {
            null
        }
    }

    

    // ---------- Package name state ----------
    var packageName by mutableStateOf(prefilledOptions.packageName)
        private set

    var packageNameState by mutableStateOf(PackageNameState.Ok)
        private set

    fun changePackageName(newPackageName: String) {
        packageName = newPackageName
        fetchPkgNameStateDebounced()
    }

    // ---------- App name state ----------
    var appName by mutableStateOf(prefilledOptions.appName)
        private set

    var appNameIsError by mutableStateOf(false)
        private set

    fun changeAppName(newAppName: String) {
        appName = newAppName
        appNameIsError = newAppName.length !in (1..150)
    }

    // ---------- Debuggable state ----------
    var debuggable by mutableStateOf(prefilledOptions.debuggable)
        private set

    fun changeDebuggable(value: Boolean) {
        debuggable = value
    }

    // ---------- Custom version code state ----------
    var customVersionCode by mutableStateOf(prefilledOptions.customVersionCode)
        private set

    var customVersionCodeIsError by mutableStateOf(
        isCustomVersionCodeInvalid(customVersionCode)
    )
        private set

    fun changeCustomVersionCode(value: String) {
        customVersionCode = value
        customVersionCodeIsError = isCustomVersionCodeInvalid(value)
    }

    private fun isCustomVersionCodeInvalid(value: String): Boolean {
        // Local APK/APKM installs use the APK's real Android versionCode, which is not
        // guaranteed to match SurgeCord's six-digit remote Discord version format.
        // The manual version field only needs strict validation when it will actually
        // be used as a developer override for remote downloads.
        return prefs.devMode && value.isNotBlank() && !DiscordVersion.isValid(value)
    }

    // ---------- Other ----------
    var showNetworkWarningDialog by mutableStateOf(!alreadyShownNetworkWarning && isNetworkDangerous())
        private set

    fun hideNetworkWarning(neverShow: Boolean) {
        showNetworkWarningDialog = false
        alreadyShownNetworkWarning = true
        prefs.showNetworkWarning = !neverShow
    }

    // ---------- Config generation ----------
    val isConfigValid by derivedStateOf {
        val invalidChecks = arrayOf(
            packageNameState == PackageNameState.Invalid,
            appNameIsError,
            customVersionCodeIsError,
        )

        invalidChecks.none { it }
    }

    fun generateConfig(icon: PatchOptions.IconReplacement): PatchOptions {
        if (!isConfigValid) error("invalid config state")

        return PatchOptions(
            appName = appName,
            packageName = packageName,
            debuggable = debuggable,
            iconReplacement = icon,

            customVersionCode = customVersionCode,
            localApkPath = localApkPath,
            isDevMode = isDevMode,
        )
    }

    // ---------- Other ----------
    val isDevMode: Boolean
        get() = prefs.devMode

    /**
     * Check whether the device is connected on a metered WIFI connection or through any type of mobile data,
     * to avoid unknowingly downloading a lot of stuff through a potentially metered network.
     */
    @Suppress("DEPRECATION")
    fun isNetworkDangerous(): Boolean {
        val connectivity = context.getSystemService<ConnectivityManager>()
            ?: error("Unable to get system connectivity service")

        if (connectivity.isActiveNetworkMetered) return true

        when (val info = connectivity.activeNetworkInfo) {
            null -> return false
            else -> {
                if (info.isRoaming) return true
                if (info.type == ConnectivityManager.TYPE_WIFI) return false
            }
        }

        val telephony = context.getSystemService<TelephonyManager>()
            ?: error("Unable to get system telephony service")

        val dangerousMobileDataStates = arrayOf(
            /* TelephonyManager.DATA_DISCONNECTING */ 4,
            TelephonyManager.DATA_CONNECTED,
            TelephonyManager.DATA_CONNECTING,
        )

        return dangerousMobileDataStates.contains(telephony.dataState)
    }

    // A throttled variant of fetchPkgNameState()
    private val fetchPkgNameStateDebounced: () -> Unit =
        screenModelScope.debounce(100L, function = ::fetchPkgNameState)

    private suspend fun fetchPkgNameState() {
        val state = if (packageName.length !in (3..150) || !PACKAGE_REGEX.matches(this.packageName)) {
            PackageNameState.Invalid
        } else {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                PackageNameState.Taken
            } catch (_: NameNotFoundException) {
                PackageNameState.Ok
            }
        }

        mainThread { packageNameState = state }
    }

    init {
        if (!prefs.showNetworkWarning)
            showNetworkWarningDialog = false

        screenModelScope.launchBlock { fetchPkgNameState() }
    }

    companion object {
        // Global state to avoid showing the warning more than once per launch
        private var alreadyShownNetworkWarning = false

        private val PACKAGE_REGEX = """^[a-z]\w*(\.[a-z]\w*)+$"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}

enum class PackageNameState {
    Ok,
    Invalid,
    Taken,
}
