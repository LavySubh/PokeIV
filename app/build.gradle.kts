plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from GitHub secrets (see README). Without them the build falls back to the debug key.
val ksFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.pokeiv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.pokeiv"
        minSdk = 26
        targetSdk = 34
        // Every GitHub build gets a higher number, so a new APK installs over the old one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + 1
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    signingConfigs {
        create("release") {
            if (ksFile != null) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "pokeiv"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
