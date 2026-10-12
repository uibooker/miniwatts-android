plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.miniwatts"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "dev.miniwatts"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "1.1" }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".tooling/debug.keystore") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}
dependencies { implementation(project(":core")) }
