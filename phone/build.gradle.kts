import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// Developer defaults are embedded only in debug APKs, as in app/build.gradle.kts.
val dotEnvFile = rootProject.file(".env").takeIf { it.isFile } ?: project.file("../.env")
val dotEnv = dotEnvFile.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { raw ->
    val line = raw.trim().removePrefix("export ").trim()
    if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
    val value = line.substringAfter('=').trim()
    line.substringBefore('=').trim() to
        if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") value.substring(1, value.length - 1)
        else value.replace(Regex("""\s+#.*$"""), "")
}.toMap()
val devKeys = listOf("DEXCOM_USERNAME", "DEXCOM_PASSWORD", "DEXCOM_REGION", "NIGHTSCOUT_URL",
    "NIGHTSCOUT_TOKEN", "NIGHTSCOUT_API", "GLUCOWATCH_SOURCE", "GLUCOWATCH_UNIT")
val devDefaults = devKeys.associateWith { System.getenv(it) ?: dotEnv[it] ?: "" }
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The optional phone app: reads Dexcom Share or Nightscout and relays it to the watch app over
// Bluetooth (docs/phone-link.md). Keep versionCode and versionName on the same pair as app/ and watchface/.
android {
    namespace = "io.github.antonkulaga.glucowatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch.phone"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.8"
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        getByName("debug") {
            devDefaults.forEach { (key, value) -> buildConfigField("String", "DEV_$key", javaString(value)) }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            devKeys.forEach { key -> buildConfigField("String", "DEV_$key", "\"\"") }
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
    // FileProvider hands the system camera app a private file to write one meal photo into.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
