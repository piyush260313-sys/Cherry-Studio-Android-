plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cherry.studio"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cherry.studio"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val ytdl = "0.17.4"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl") { isTransitive = false }
    implementation("io.github.junkfood02.youtubedl-android:common:$ytdl") { isTransitive = false }
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl") { isTransitive = false }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-compress:1.26.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
