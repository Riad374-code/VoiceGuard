import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun loadRootProperties(fileName: String): Properties =
    Properties().apply {
        val propertiesFile = rootProject.file(fileName)
        if (propertiesFile.isFile) {
            propertiesFile.inputStream().use(::load)
        }
    }

val dotenvProperties = loadRootProperties(".env")
val localProperties = loadRootProperties("local.properties")

fun buildConfigString(name: String, fallback: String = ""): String {
    val rawValue = providers.environmentVariable(name).orNull
        ?: dotenvProperties.getProperty(name)
        ?: localProperties.getProperty(name)
        ?: fallback
    val escapedValue = rawValue
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escapedValue\""
}

android {
    namespace = "com.guardvoice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.guardvoice"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GROQ_API_KEY", buildConfigString("GROQ_API_KEY"))
        buildConfigField(
            "String",
            "GROQ_WHISPER_MODEL",
            buildConfigString("GROQ_WHISPER_MODEL", "whisper-large-v3-turbo")
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.00"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
