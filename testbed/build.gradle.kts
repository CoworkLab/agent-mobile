plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * The testbed app.
 *
 * A separate APK, never part of a release: it exists only so the scenario suite has screens
 * with the difficulties real apps present — a custom-drawn timeline with no accessibility
 * nodes, a button that does nothing, a slow load, a screen that blocks capture. Shipping it
 * would put a fake bank on the user's phone, so the release pipeline excludes it and the
 * versionCode is fixed rather than taken from CI.
 */
android {
    namespace = "com.phoneagent.testbed"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneagent.testbed"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Plain Views, no Compose: the testbed is meant to look like an ordinary app's view tree,
    // and Compose's semantics layer would make it easier than the real thing.
    implementation(libs.androidx.core.ktx)
}
