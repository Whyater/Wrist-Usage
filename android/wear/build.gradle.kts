import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The snapshot URL is a build-time setting kept out of version control.
// Put `snapshotUrl=https://gist.githubusercontent.com/.../usage.json` in android/local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val snapshotUrl: String = (localProps.getProperty("snapshotUrl") ?: "").trim()

val releaseKeys = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.whyater.wristusage"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.whyater.wristusage"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        val escaped = snapshotUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SNAPSHOT_URL", "\"$escaped\"")
    }

    // The phone and watch apps must share one signing key, or the Data Layer will not connect them.
    // android/keystore.properties (not in git) holds storeFile, storePassword, keyAlias and keyPassword.
    // Without it, release builds fall back to this machine's debug key.
    signingConfigs {
        if (releaseKeys.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation(project(":shared"))

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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.google.play.services.wearable)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.wear.protolayout.proto)
    testImplementation(libs.org.json)
}
