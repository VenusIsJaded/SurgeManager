@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

val requestedReleaseBuild = System.getenv("RELEASE")?.toBoolean() ?: false
val signingStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = signingStoreFile != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null &&
    signingStorePassword != null
val isRelease = requestedReleaseBuild

val gitCurrentBranch = providers.execIgnoreStderr("git", "symbolic-ref", "--quiet", "--short", "HEAD").takeIf { it.isNotEmpty() }
val gitLatestCommit = providers.execIgnoreStderr("git", "rev-parse", "--short", "HEAD").takeIf { it.isNotEmpty() } ?: "unknown"
val gitHasLocalCommits = gitCurrentBranch?.let { providers.execIgnoreStderr("git", "log", "origin/$gitCurrentBranch..HEAD").isNotEmpty() } ?: false
val gitHasHasLocalChanges = providers.execIgnoreStderr("git", "status", "-s").isNotEmpty()

android {
    namespace = "dev.surgecord.manager"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        versionCode = 1010
        versionName = "v1.0.1"

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "APPLICATION_NAME", "\"Surge Manager\"")
        buildConfigField("String", "TAG", "\"SurgeManager\"")

        buildConfigField("String", "CODEBERG_ORG", "\"VenusIsJaded\"")
        buildConfigField("String", "GITHUB_ORG", "\"VenusIsJaded\"")
        buildConfigField("String", "SUPPORT_SERVER", "\"6cN7wKa8gp\"")

        buildConfigField("String", "BACKEND_URL", "\"https://surgecord.dev/\"")

        buildConfigField("Boolean", "RELEASE", isRelease.toString())
        buildConfigField("String", "GIT_BRANCH", "\"${gitCurrentBranch ?: "detached"}\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitLatestCommit\"")
        buildConfigField("boolean", "GIT_LOCAL_COMMITS", "$gitHasLocalCommits")
        buildConfigField("boolean", "GIT_LOCAL_CHANGES", "$gitHasHasLocalChanges")
    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
            keyAlias = signingKeyAlias
            keyPassword = signingKeyPassword
            storeFile = signingStoreFile
            storePassword = signingStorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        create("staging") {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidComponents {
        onVariants(selector().withBuildType("release")) {
            it.packaging.resources.excludes.apply {
                add("/**/*.version")
                add("/kotlin-tooling-metadata.json")
                add("/DebugProbesKt.bin")
                add("/**/*.kotlin_builtins")
            }
        }
    }

    packaging {
        resources {
            excludes += "/okhttp3/**"
            excludes += "/*.properties"
            excludes += "/org/antlr/**"
            excludes += "/com/android/tools/smali/**"
            excludes += "/org/eclipse/jgit/**"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/org/bouncycastle/**"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        disable += "ModifierParameter"
    }
}

kotlin {
    sourceSets.all {
        languageSettings.enableLanguageFeature("ExplicitBackingFields")
    }
    compilerOptions {
        val reportsDir = layout.buildDirectory.asFile.get()
            .resolve("reports").absolutePath

        jvmTarget = JvmTarget.JVM_1_8
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.time.ExperimentalTime",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=${reportsDir}",
            "-XXLanguage:+PropertyParamAnnotationDefaultTargetMode",
        )
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    implementation(libs.bundles.accompanist)
    implementation(libs.bundles.androidx)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.compose)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.ktor)
    implementation(libs.bundles.voyager)

    implementation(files("libs/lspatch.aar"))

    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.runtime.tracing)

    implementation(libs.kotlinx.immutable)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.apksig)
    implementation(libs.axml)
    implementation(libs.bouncycastle)
    implementation(libs.binaryResources)
    implementation(libs.diff)
    implementation(libs.microg)
    implementation(libs.smali)
    implementation(libs.baksmali)
    implementation(libs.compose.pipette)
    implementation(libs.compose.shimmer)
    implementation(libs.zip)

    coreLibraryDesugaring(libs.desugaring)
}

configurations.all {
    exclude(group = "org.bouncycastle")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "com.aliucord", module = "axml")
    exclude(group = "com.android.tools.build", module = "apksig")
    exclude(group = "com.beust", module = "jcommander")
    exclude(group = "com.google.guava")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "com.google.code.findbugs", module = "jsr305")
}

fun ProviderFactory.execIgnoreStderr(vararg command: String): String {
    val result = exec {
        commandLine(*command)
        isIgnoreExitValue = true
    }
    // Only use stdout, ignore stderr (git commands often write to stderr even on success)
    return result.standardOutput.asText.get().trim()
}
