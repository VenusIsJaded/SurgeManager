/*
 * Copyright (c) 2022 Juby210 & zt
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.manager.ui.screens.home

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.aliucord.manager.patcher.util.ApkFileInfo
import com.aliucord.manager.patcher.util.extractApkFileInfo
import com.aliucord.manager.ui.components.LoadFailure
import com.aliucord.manager.ui.components.ProjectHeader
import com.aliucord.manager.ui.components.dialogs.InstallOptionsBottomSheet
import com.aliucord.manager.ui.screens.home.components.*
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import com.aliucord.manager.ui.screens.patchopts.PatchOptionsScreen
import com.aliucord.manager.ui.screens.plugins.PluginsScreen
import com.aliucord.manager.ui.util.paddings.PaddingValuesSides
import com.aliucord.manager.ui.util.paddings.exclude
import com.aliucord.manager.util.*
import dev.surgecord.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
class HomeScreen : Screen, Parcelable {
    @IgnoredOnParcel
    override val key = "Home"

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val model = koinScreenModel<HomeModel>()
        val context = LocalContext.current

        if (model.showInstallOptions) {
            InstallOptionsBottomSheet(
                onDismiss = { model.showInstallOptions = false },
                onAutoInstall = { navigator.push(PatchOptionsScreen()) },
                onLocalApkSelected = { uri ->
                    // Sheet is already dismissed by InstallOptionsBottomSheet before calling here.
                    // The URI has a persistable permission taken, so it is safe to use on IO thread.
                    scope.launch {
                        val (localPath, pkgInfo) = withContext(Dispatchers.IO) {
                            processSelectedApk(context, uri)
                        }
                        if (localPath != null) {
                            val opts = PatchOptions(
                                appName = "SurgeCord",
                                packageName = pkgInfo?.packageName ?: "com.discord",
                                debuggable = false,
                                iconReplacement = PatchOptions.IconReplacement.CustomColor(PatchOptions.IconReplacement.SurgeColor),
                                customVersionCode = pkgInfo?.versionCode?.toString() ?: "",
                                localApkPath = localPath,
                                isDevMode = false
                            )
                            navigator.push(PatchOptionsScreen(opts))
                        }
                    }
                }
            )
        }

        LifecycleResumeEffect(Unit) {
            model.refresh(delay = true)
            onPauseOrDispose {}
        }

        Scaffold(
            topBar = { HomeAppBar() },
        ) { padding ->
            when (val state = model.installsState) {
                is InstallsState.Fetched -> HomeScreenLoadedContent(
                    state = state,
                    padding = padding,
                    onClickInstall = { model.showInstallOptions = true },
                    onUpdate = {
                        scope.launchIO {
                            val screen = model.createPrefilledPatchOptsScreen(it)
                            mainThread { navigator.push(screen) }
                        }
                    },
                    onOpenApp = model::openApp,
                    onOpenAppInfo = model::openAppInfo,
                    onOpenPlugins = { navigator.push(PluginsScreen()) },
                )

                InstallsState.Fetching -> HomeScreenLoadingContent(padding = padding)
                InstallsState.None -> HomeScreenNoneContent(
                    padding = padding,
                    onClickInstall = { model.showInstallOptions = true },
                )

                InstallsState.Error -> HomeScreenFailureContent(padding = padding)
            }
        }
    }
}

/**
 * Processes the selected APK/APKM URI: copies/extracts to a persistent cache location
 * and returns the local path and package info.
 *
 * Must be called from a background thread (Dispatchers.IO).
 * The caller must ensure the URI's read permission is still valid (e.g. persistable permission taken).
 */
private fun processSelectedApk(
    context: Context,
    uri: Uri,
): Pair<String?, ApkFileInfo?> {
    val fileName = getFileNameFromUri(context, uri) ?: "local.apk"

    // Use a persistent directory that won't be cleared by Android.
    // Keep APKM/APK set archives intact here; CopyDependenciesStep expands the base
    // and split APKs later so PackageManager receives a complete install session.
    val targetDir = File(context.filesDir, "local_apks")
    val targetFile = File(
        targetDir,
        fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "local.apk" }
    )

    return try {
        if (targetDir.exists() && !targetDir.isDirectory) {
            targetDir.delete()
        }

        if (targetDir.exists()) {
            targetDir.listFiles()?.forEach { it.deleteRecursively() }
        } else if (!targetDir.mkdirs() && !targetDir.exists()) {
            Log.e("HomeScreen", "Failed to create local APK cache dir: ${targetDir.absolutePath}")
            return null to null
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        } ?: run {
            Log.e("HomeScreen", "openInputStream returned null for local APK/APKM URI")
            return null to null
        }

        val pkgInfo = extractApkFileInfo(context, targetFile)
        Log.i("HomeScreen", "Copied local APK/APKM: ${targetFile.absolutePath}, info: $pkgInfo")
        targetFile.absolutePath to pkgInfo
    } catch (e: Exception) {
        Log.e("HomeScreen", "Failed to process selected APK/APKM", e)
        null to null
    }
}

/**
 * Gets the file name from a content URI.
 */
private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    val cursor = try {
        context.contentResolver.query(uri, null, null, null, null)
    } catch (_: Exception) {
        null
    }

    cursor?.use {
        val nameIndex = it.getColumnIndex("_display_name")
        if (nameIndex >= 0 && it.moveToFirst()) {
            return it.getString(nameIndex)
        }
    }

    // Fallback: extract from the URI path
    return uri.path?.substringAfterLast("/")
        ?.takeIf { it.isNotBlank() }
}

@Composable
fun HomeScreenLoadingContent(padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        ProjectHeader()

        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false) }.apply { targetState = true },
            enter = fadeIn(animationSpec = tween(durationMillis = 800)),
            exit = ExitTransition.None,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                content = { CircularProgressIndicator() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun HomeScreenLoadedContent(
    state: InstallsState.Fetched,
    padding: PaddingValues,
    onClickInstall: () -> Unit,
    onUpdate: (packageName: String) -> Unit,
    onOpenApp: (packageName: String) -> Unit,
    onOpenAppInfo: (packageName: String) -> Unit,
    onOpenPlugins: (packageName: String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = padding
            .exclude(PaddingValuesSides.Horizontal + PaddingValuesSides.Top),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding.exclude(PaddingValuesSides.Bottom))
            .padding(top = 16.dp, start = 16.dp, end = 16.dp),
    ) {
        item(key = "PROJECT_HEADER") {
            ProjectHeader()
        }

        item(key = "ADD_INSTALL_BUTTON") {
            InstallButton(
                secondaryInstall = true,
                onClick = onClickInstall,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .height(50.dp)
                    .fillMaxWidth()
            )
        }

        items(state.data, key = { it.packageName }) { item ->
            InstalledItemCard(
                data = item,
                onUpdate = { onUpdate(item.packageName) },
                onOpenApp = { onOpenApp(item.packageName) },
                onOpenInfo = { onOpenAppInfo(item.packageName) },
                onOpenPlugins = { onOpenPlugins(item.packageName) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun HomeScreenNoneContent(
    padding: PaddingValues,
    onClickInstall: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        ProjectHeader()

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.installs_no_installs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .alpha(0.6f)
                .padding(bottom = 16.dp)
        )

        InstallButton(
            secondaryInstall = false,
            onClick = onClickInstall,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .height(50.dp)
                .fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun HomeScreenFailureContent(padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        ProjectHeader()
        LoadFailure()
    }
}
