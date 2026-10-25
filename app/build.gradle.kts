import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.miniwatts"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.miniwatts"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.16"
        testInstrumentationRunner = "dev.miniwatts.RegressionRunner"
    }
    buildTypes.getByName("debug") {
        if (providers.gradleProperty("isolatedDebug").orNull == "true") {
            applicationIdSuffix = ".macdebug"
            resValue("string", "app_name", "MiniWatts Test")
        }
    }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".tooling/debug.keystore") }
    // A release APK has to be signed to install, and the signing key is the app's permanent update
    // identity, so it deliberately lives outside `.tooling/` (which is disposable scratch space) and
    // its password lives in the gitignored local.properties rather than in this file. On a machine
    // without that file the release build falls back to the debug identity so it still builds.
    signingConfigs.create("release") {
        val local =
            Properties().apply {
                rootProject
                    .file("local.properties")
                    .takeIf { it.exists() }
                    ?.inputStream()
                    ?.use { load(it) }
            }
        val store = local.getProperty("release.storeFile")?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            storeFile = store
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
        }
    }
    buildTypes.getByName("release") {
        signingConfig =
            signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    // Optional privileged channel. Shizuku is not root: the app sends fixed, read-only work to a
    // user service that runs inside the Shizuku server process (ADB shell or root, whichever the
    // user started). Without Shizuku every privileged reading stays "unavailable".
    implementation("dev.rikka.shizuku:api:13.1.5") {
        exclude(group = "androidx.annotation", module = "annotation")
    }
    implementation("dev.rikka.shizuku:provider:13.1.5") {
        // This project keeps android.useAndroidX=false; these are CLASS-retention annotations.
        exclude(group = "androidx.annotation", module = "annotation")
    }
}
