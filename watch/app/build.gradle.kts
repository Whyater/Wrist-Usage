import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The snapshot URL is a build-time setting kept out of version control.
// Put `snapshotUrl=https://gist.githubusercontent.com/.../usage.json` in watch/local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val snapshotUrl: String = (localProps.getProperty("snapshotUrl") ?: "").trim()

android {
    namespace = "io.github.whyater.wristusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.whyater.wristusage"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        val escaped = snapshotUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SNAPSHOT_URL", "\"$escaped\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded personal app: sign with the debug key so `assembleRelease` yields an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.complications.data.source.ktx)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.wear.protolayout.proto)
    testImplementation(libs.org.json)
}
