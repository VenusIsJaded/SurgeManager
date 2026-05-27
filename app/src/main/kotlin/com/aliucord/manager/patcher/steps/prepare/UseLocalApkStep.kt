package com.aliucord.manager.patcher.steps.prepare

import android.content.Context
import androidx.compose.runtime.Stable
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import com.aliucord.manager.patcher.util.extractApkFileInfo
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import dev.surgecord.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.properties.Delegates

/**
 * When a local APK/APKM is provided, read its metadata (version code, version name, package name)
 * so that subsequent steps can use this information instead of fetching from the network.
 *
 * This step does NOT copy the APK - it only reads its metadata.
 * The actual APK copying/extraction happens in CopyDependenciesStep.
 */
@Stable
class UseLocalApkStep(private val options: PatchOptions) : Step(), KoinComponent {
    private val context: Context by inject()

    override val group = StepGroup.Prepare
    override val localizedName = R.string.patch_step_use_local_apk

    /**
     * The version code extracted from the local APK.
     */
    var localVersion: Int by Delegates.notNull()
        private set

    /**
     * The version name extracted from the local APK.
     */
    var localVersionName: String by Delegates.notNull()
        private set

    /**
     * The package name extracted from the local APK.
     */
    var localPackageName: String by Delegates.notNull()
        private set

    override suspend fun execute(container: StepRunner) {
        val localApkPath = options.localApkPath
        if (localApkPath == null || localApkPath.isBlank()) {
            container.log("No local APK path provided, skipping")
            state = StepState.Skipped
            return
        }

        val sourceFile = File(localApkPath)
        if (!sourceFile.exists()) {
            container.log("Local APK file does not exist: $localApkPath")
            throw Error("Local APK file not found: $localApkPath")
        }

        container.log("Reading local APK metadata: ${sourceFile.absolutePath} (${sourceFile.length() / 1024 / 1024}MB)")

        val apkInfo = extractApkFileInfo(context, sourceFile)
            ?: throw Error("Failed to parse local APK/APKM package info. The APK may be corrupted or incompatible.")

        localPackageName = apkInfo.packageName
        localVersion = apkInfo.versionCode.toInt()
        localVersionName = apkInfo.versionName

        container.log("Extracted APK info: package=$localPackageName, versionCode=$localVersion, versionName=\"$localVersionName\"")
    }
}
