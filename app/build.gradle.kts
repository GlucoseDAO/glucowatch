import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

// Developer defaults from <project>/.env (see .env.example), same rules as core's DotEnv.
// Compiled into DEBUG builds only; release builds always get empty strings.
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { raw ->
    val line = raw.trim().removePrefix("export ").trim()
    if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
    val value = line.substringAfter('=').trim()
    line.substringBefore('=').trim() to
        if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") value.substring(1, value.length - 1)
        else value.replace(Regex("""\s+#.*$"""), "")
}.toMap()

val devDefaults = listOf("DEXCOM_USERNAME", "DEXCOM_PASSWORD", "DEXCOM_REGION", "GLUCOWATCH_UNIT", "GLUCOWATCH_PREDICTION")
    .associateWith { System.getenv(it) ?: dotEnv[it] ?: "" }

fun checkDefault(key: String, allowed: Set<String>) = devDefaults.getValue(key).lowercase().let {
    require(it.isEmpty() || it in allowed) { ".env: $key must be one of $allowed, got '$it'" }
}
checkDefault("DEXCOM_REGION", setOf("eu", "ous", "us", "jp"))
checkDefault("GLUCOWATCH_UNIT", setOf("mmol", "mmol/l", "mgdl", "mg/dl"))
checkDefault("GLUCOWATCH_PREDICTION", setOf("true", "false"))

fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.antonkulaga.glucowatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            devDefaults.forEach { (key, value) -> buildConfigField("String", "DEV_$key", javaString(value)) }
        }
        release {
            isMinifyEnabled = false
            devDefaults.keys.forEach { key -> buildConfigField("String", "DEV_$key", "\"\"") }
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
