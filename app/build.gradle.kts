import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
val keystoreProperties = loadRootProperties("keystore.properties")
val debugKeystoreFile = rootProject.file("${System.getProperty("user.home")}/.android/debug.keystore")

/** API keys: local.properties wins, then .env, then env var, then "". Never hardcoded. */
fun secretField(name: String, vararg aliases: String): String {
    val names = listOf(name) + aliases
    var raw: String? = null
    for (n in names) {
        raw = localProperties.getProperty(n)
            ?: dotenvProperties.getProperty(n)
            ?: providers.environmentVariable(n).orNull
        if (!raw.isNullOrEmpty()) break
    }
    val v = (raw ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$v\""
}

android {
    namespace = "com.voiceguard"
    // Spec says 34, but the Compose BOM libraries require compiling against
    // 35/36 — safest option is compileSdk 36 with targetSdk kept at 34.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voiceguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "DEEPGRAM_API_KEY", secretField("DEEPGRAM_API_KEY", "DEEPGRAM_SST"))
        buildConfigField("String", "GROQ_API_KEY", secretField("GROQ_API_KEY"))
        buildConfigField("String", "DEEPGRAM_MODEL", secretField("DEEPGRAM_DEFAULT_MODEL").let {
            if (it == "\"\"") "\"nova-2\"" else it
        })
    }

    signingConfigs {
        create("release") {
            val storeFileProp = keystoreProperties.getProperty("storeFile").orEmpty()
            val storeFileRef = rootProject.file(storeFileProp)
            if (storeFileProp.isNotBlank() && storeFileRef.isFile) {
                storeFile = storeFileRef
                storePassword = keystoreProperties.getProperty("storePassword").orEmpty()
                keyAlias = keystoreProperties.getProperty("keyAlias").orEmpty()
                keyPassword = keystoreProperties.getProperty("keyPassword").orEmpty()
            } else if (debugKeystoreFile.isFile) {
                // No release keystore configured — fall back to the local debug key so
                // assembleRelease still yields an installable APK for direct sharing.
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
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

dependencies {    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.icons.extended)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
