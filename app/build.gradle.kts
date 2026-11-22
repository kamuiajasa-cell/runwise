plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystoreFile = System.getenv("RUNSENSE_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("RUNSENSE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RUNSENSE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RUNSENSE_KEY_PASSWORD")

android {
    namespace = "id.kamui.runsense"
    compileSdk = 36
    defaultConfig {
        applicationId = "id.kamui.runsense"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("VERSION_CODE").orElse(providers.environmentVariable("GITHUB_RUN_NUMBER")).orElse("2").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orElse("2.0.0-native").get()
    }
    signingConfigs {
        create("release") {
            if (!releaseKeystoreFile.isNullOrBlank()) {
                storeFile = file(releaseKeystoreFile)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":huawei"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}
