plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.antonkulaga.glucowatch.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch.watchface"
        // Watch Face Format v1 needs Wear OS 4 (API 33) or newer.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}
