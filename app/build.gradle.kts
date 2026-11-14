import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keys live only in local.properties (git-ignored, never committed). Without them the app runs with the AI chat off.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localString(name: String): String =
    "\"" + (localProps.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.bureung.english"
    compileSdk = 34
    // AAOS car API (gear, outside temperature). Optional at runtime: see <uses-library required="false"> in the manifest.
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "app.bureung.english"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.6.0"

        buildConfigField("String", "GEMINI_API_KEY", localString("gemini.apiKey"))
        // optional: other model names, if the defaults in the code have been retired
        buildConfigField("String", "GEMINI_MODEL", localString("gemini.model"))
        buildConfigField("String", "GEMINI_TTS_MODEL", localString("gemini.ttsModel"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Reviewers install the APK straight onto the emulator, so the release build is signed with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Pleos Playground SDKs (no Client ID/Secret needed: voice runs in ON_DEVICE mode)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")

    testImplementation("junit:junit:4.13.2")
}
