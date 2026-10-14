plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.whyater.wristusage.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
