import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// The optional phone app: reads Dexcom Share or Nightscout and relays it to the watch app over
// Bluetooth (docs/phone-link.md). Keep versionCode and versionName on the same pair as app/ and watchface/.
android {
    namespace = "io.github.antonkulaga.glucowatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch.phone"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
