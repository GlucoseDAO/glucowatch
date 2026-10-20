import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// LF for everything packaged byte for byte, so the APK matches F-Droid's build on any OS.
apply { from("../gradle/line-endings.gradle.kts") }

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
    "NIGHTSCOUT_TOKEN", "NIGHTSCOUT_API", "GLUCOWATCH_SOURCE", "GLUCOWATCH_UNIT",
    "GLUCOWATCH_LATEST_DEVICE", "GLUCOWATCH_DEVICE_SINCE", "HF_TOKEN", "HF_MODEL_ADDRESS",
    "CARELINK_COUNTRY", "CARELINK_USERNAME", "CARELINK_PASSWORD")
val devDefaults = devKeys.associateWith { key ->
    val alias = if (key == "HF_TOKEN") "HUGGINGFACE_TOKEN" else null
    val value = (System.getenv(key) ?: alias?.let(System::getenv) ?: dotEnv[key] ?: alias?.let(dotEnv::get) ?: "").trim()
    if (key == "CARELINK_COUNTRY") value.uppercase() else value
}
val carelinkCountry = devDefaults.getValue("CARELINK_COUNTRY")
require(carelinkCountry.isEmpty() || carelinkCountry.matches(Regex("[A-Z]{2}"))) {
    ".env: CARELINK_COUNTRY must be two letters, such as DE"
}
val latestDevice = devDefaults.getValue("GLUCOWATCH_LATEST_DEVICE").lowercase().replace('-', '_').replace(' ', '_')
require(latestDevice.isEmpty() || latestDevice in setOf(
    "dexcom", "dexcom_sensor", "sensor", "minimed", "minimed_pump", "pump", "carelink", "medtronic",
)) { ".env: GLUCOWATCH_LATEST_DEVICE must be dexcom_sensor or minimed_pump, got '$latestDevice'" }
val deviceSince = devDefaults.getValue("GLUCOWATCH_DEVICE_SINCE")
require(deviceSince.isEmpty() || isoDate(deviceSince)) {
    ".env: GLUCOWATCH_DEVICE_SINCE must be yyyy-MM-dd, got '$deviceSince'"
}

fun isoDate(value: String): Boolean {
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(value) ?: return false
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    if (month !in 1..12) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val length = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return day in 1..length[month - 1]
}
fun javaString(value: String) = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""

// The optional phone app: reads Dexcom Share or Nightscout and relays it to the watch app over
// Bluetooth (docs/phone-link.md). Keep versionCode and versionName on the same pair as app/ and watchface/.
android {
    namespace = "io.github.antonkulaga.glucowatch.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.antonkulaga.glucowatch.phone"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "0.1.15"
    }

    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").kotlin.directories.add(file("../onnx-inference").absolutePath)

    buildTypes {
        getByName("debug") {
            devDefaults.forEach { (key, value) -> buildConfigField("String", "DEV_$key", javaString(value)) }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // MIT-licensed CPU runtime from Maven Central. No runtime or model binaries in Git.
    // 1.28 predates Android telemetry; verify packaging/privacy before upgrading.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation(project(":core"))
    // FileProvider hands the system camera app a private file to write one meal photo into.
    implementation("androidx.core:core-ktx:1.17.0")
    // Origin-restricted document-start CSV capture; Apache-2.0, from Google's Maven repository.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
