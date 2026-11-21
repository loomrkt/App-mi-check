import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

fun dartDefine(name: String): String? {
    val raw = (project.findProperty("dart-defines") as? String) ?: return null
    return raw.split(",")
        .mapNotNull { entry ->
            runCatching { String(Base64.getDecoder().decode(entry), Charsets.UTF_8) }.getOrNull()
        }
        .mapNotNull { define ->
            val separator = define.indexOf('=')
            if (separator <= 0) null else define.substring(0, separator) to define.substring(separator + 1)
        }
        .firstOrNull { it.first == name }
        ?.second
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val deepSeekKey = (
    dartDefine("DEEPSEEK_API_KEY")
        ?: System.getenv("DEEPSEEK_API_KEY")
        ?: localProperties.getProperty("DEEPSEEK_API_KEY")
        ?: (project.findProperty("DEEPSEEK_API_KEY") as? String)
        ?: ""
).trim()

val deepSeekKeyLiteral = deepSeekKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.madadiaspora.chekeov2"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.madadiaspora.chekeov2"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekKeyLiteral\"")
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
