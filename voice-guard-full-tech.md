# 📱 AI Scam Call Detector — Full App Blueprint
### Android (Kotlin + Android Studio)

---

## 🧭 Table of Contents

1. [What This App Does](#what-this-app-does)
2. [Full User Journey](#full-user-journey)
3. [System Architecture](#system-architecture)
4. [Tech Stack & Tools](#tech-stack--tools)
   - [Development Environment](#️-development-environment)
   - [Full build.gradle.kts](#-full-buildgradlekts-app-level)
   - [libs.versions.toml](#-libsversionstoml-version-catalog)
   - [Kotlin Language Features](#-kotlin-language-features-used-and-why)
   - [Networking Layer (Retrofit)](#-networking-layer--retrofit-setup-in-detail)
   - [UI Layer: Compose vs XML](#-ui-layer--compose-vs-xml-when-to-use-each)
   - [Data Layer (Room)](#️-data-layer--room-database-setup)
   - [AI/Backend Services Detail](#-ai--backend-services--full-detail)
   - [Foreground Service Setup](#-foreground-service--keeping-audio-alive)
5. [Android APIs Used](#android-apis-used)
6. [Audio Pipeline](#audio-pipeline)
7. [AI Analysis Pipeline](#ai-analysis-pipeline)
8. [Data Flow Diagram](#data-flow-diagram)
9. [Permissions Breakdown](#permissions-breakdown)
10. [Project Structure](#project-structure)
11. [Build Order (Phase by Phase)](#build-order-phase-by-phase)
12. [OEM Compatibility Notes](#oem-compatibility-notes)
13. [Play Store Considerations](#play-store-considerations)
14. [Known Limitations](#known-limitations)

---

## What This App Does

An AI-powered real-time scam detector that:

- Watches for **incoming calls from unsaved numbers**
- Asks the **user for consent** before doing anything
- If the user says YES → **auto-switches to speakerphone**
- **Captures both voices** via the microphone (speakerphone makes both sides audible)
- **Streams audio in real-time** to a speech-to-text service
- **Feeds the transcript** to an LLM that scores scam likelihood
- Displays a **live verdict overlay** on screen during the call

Everything is opt-in. The user is always in control.

---

## Full User Journey

```
┌─────────────────────────────────────────────────────┐
│  STEP 1 — First Launch (One Time Setup)             │
│                                                     │
│  App opens → Explains what it does                  │
│  → Requests permissions (mic, phone state, overlay) │
│  → Asks user to set app as Caller ID & Spam handler │
│  → Setup complete ✅                                │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│  STEP 2 — Incoming Call Detected                    │
│                                                     │
│  Unsaved number calls the user                      │
│  → CallScreeningService fires immediately           │
│  → App checks contacts: "Is this number saved?"    │
│  → Number is NOT saved → proceed                    │
│  → Call is allowed to ring normally (not blocked)   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│  STEP 3 — Consent Overlay Appears                   │
│                                                     │
│  While phone is ringing:                            │
│  ┌──────────────────────────────┐                  │
│  │  📞 Unknown Number           │                  │
│  │  +994 50 XXX XX XX           │                  │
│  │                              │                  │
│  │  Want AI to analyze          │                  │
│  │  this call for scams?        │                  │
│  │                              │                  │
│  │  [YES, ANALYZE] [NO THANKS]  │                  │
│  └──────────────────────────────┘                  │
│                                                     │
│  User taps NO  → Normal call, app does nothing      │
│  User taps YES → App activates analysis mode        │
└─────────────────────────────────────────────────────┘
                        ↓ (if YES)
┌─────────────────────────────────────────────────────┐
│  STEP 4 — Call Connects + Speaker Activates         │
│                                                     │
│  User answers the call                              │
│  → InCallService detects STATE_ACTIVE               │
│  → App sets audio route to SPEAKER automatically   │
│  → Small toast: "🔊 Speaker on for AI analysis"     │
│  → AudioRecord begins capturing from microphone     │
│     (speakerphone makes both voices go through mic) │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│  STEP 5 — Real-Time Analysis Begins                 │
│                                                     │
│  Every 3 seconds:                                   │
│  Audio chunk → Whisper API → Transcript segment     │
│  Transcript → LLM (GPT-4o / Gemini) → Scam score   │
│  Score → Updates overlay on screen                  │
│                                                     │
│  Live overlay shows:                                │
│  ┌──────────────────────────┐                      │
│  │ 🟡 ANALYZING...          │                      │
│  │ "...your account has     │                      │
│  │  been compromised..."    │                      │
│  │                          │                      │
│  │ Risk: MEDIUM 47%         │                      │
│  └──────────────────────────┘                      │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│  STEP 6 — Verdict Updates in Real Time              │
│                                                     │
│  🟢 SAFE       — Normal conversation detected       │
│  🟡 SUSPICIOUS — Flagged phrases detected           │
│  🔴 SCAM       — High confidence scam pattern       │
│                                                     │
│  If 🔴 SCAM detected:                               │
│  → Overlay pulses red                               │
│  → Vibration alert                                  │
│  → Shows WHY: "Mentioned gift cards + urgency"      │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│  STEP 7 — Call Ends                                 │
│                                                     │
│  → Recording stops                                  │
│  → Full transcript saved locally                    │
│  → Summary card shown: full call verdict + reasons  │
│  → Option to block the number                       │
│  → Option to report to database                     │
└─────────────────────────────────────────────────────┘
```

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        YOUR APP                             │
│                                                             │
│  ┌──────────────────┐     ┌──────────────────────────────┐  │
│  │ CallScreening    │     │ InCallService                │  │
│  │ Service          │────▶│ (manages active call)        │  │
│  │ (detects call,   │     │ - switches to speaker        │  │
│  │  checks contacts)│     │ - triggers audio capture     │  │
│  └──────────────────┘     └──────────────────────────────┘  │
│                                         │                   │
│  ┌──────────────────┐                   ▼                   │
│  │ Overlay UI       │     ┌──────────────────────────────┐  │
│  │ (SYSTEM_ALERT_   │◀────│ AudioCaptureService          │  │
│  │  WINDOW)         │     │ (AudioRecord →               │  │
│  │ - consent prompt │     │  VOICE_COMMUNICATION source) │  │
│  │ - live verdict   │     └──────────────────────────────┘  │
│  └──────────────────┘                   │                   │
│                                         ▼                   │
│                           ┌──────────────────────────────┐  │
│                           │ AI Pipeline                  │  │
│                           │ Audio → Whisper → Transcript │  │
│                           │ Transcript → LLM → Score     │  │
│                           └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Tech Stack & Tools

---

### 🛠️ Development Environment

| Tool | Version | Purpose |
|------|---------|---------|
| **Android Studio** | Hedgehog (2023.1.1) or newer | Main IDE — use stable channel, not canary |
| **Kotlin** | 2.0.x | Primary language |
| **JVM Target** | 17 | Required for modern Android toolchain |
| **Gradle** | 8.4+ | Build system |
| **AGP (Android Gradle Plugin)** | 8.3.x | Matches Gradle 8.4 |
| **Min SDK** | API 29 (Android 10) | `CallScreeningService` fully stable from here |
| **Target SDK** | API 35 (Android 15) | Latest stable as of 2025 |
| **Compile SDK** | API 35 | Must match or exceed targetSdk |

---

### 📦 Full `build.gradle.kts` (App Level)

This is your exact dependency block. Copy this directly.

```kotlin
// app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)                  // for Room annotation processing
    alias(libs.plugins.hilt)                 // dependency injection
}

android {
    namespace = "com.yourapp.scamdetector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourapp.scamdetector"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true   // for storing API keys in BuildConfig
    }
}

dependencies {

    // ─── Kotlin Core ──────────────────────────────────────────
    implementation(libs.androidx.core.ktx)                    // 1.13.x
    implementation(libs.androidx.lifecycle.runtime.ktx)       // 2.8.x

    // ─── Jetpack Compose ──────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))       // BOM 2024.09.x
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)           // Material 3
    implementation(libs.androidx.activity.compose)            // 1.9.x
    implementation(libs.androidx.navigation.compose)          // 2.8.x
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ─── Lifecycle + ViewModel ────────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.ktx)     // 2.8.x
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)           // for LifecycleService

    // ─── Kotlin Coroutines ────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)           // 1.8.x
    implementation(libs.kotlinx.coroutines.core)

    // ─── Networking ───────────────────────────────────────────
    implementation(libs.retrofit)                             // 2.11.x
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)                               // 4.12.x
    implementation(libs.okhttp.logging.interceptor)           // for debugging API calls

    // ─── JSON ─────────────────────────────────────────────────
    implementation(libs.gson)                                 // 2.10.x

    // ─── Room (local database) ────────────────────────────────
    implementation(libs.androidx.room.runtime)                // 2.6.x
    implementation(libs.androidx.room.ktx)                    // coroutine support
    ksp(libs.androidx.room.compiler)                          // annotation processor

    // ─── DataStore (settings/preferences) ────────────────────
    implementation(libs.androidx.datastore.preferences)       // 1.1.x

    // ─── Hilt (Dependency Injection) ──────────────────────────
    implementation(libs.hilt.android)                         // 2.51.x
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ─── Permissions ──────────────────────────────────────────
    implementation(libs.accompanist.permissions)              // 0.34.x (Compose permission handling)
}
```

---

### 📋 `libs.versions.toml` (Version Catalog)

```toml
# gradle/libs.versions.toml

[versions]
agp = "8.3.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.03"
navigationCompose = "2.8.2"
coroutines = "1.8.1"
retrofit = "2.11.0"
okhttp = "4.12.0"
gson = "2.10.1"
room = "2.6.1"
datastore = "1.1.1"
hilt = "2.51.1"
hiltNavigationCompose = "1.2.0"
accompanistPermissions = "0.36.0"

[libraries]
# Core
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycleRuntimeKtx" }

# Compose
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

# Coroutines
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }

# Networking
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-converter-gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging-interceptor = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

# Room
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# DataStore
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }

# Permissions
accompanist-permissions = { group = "com.google.accompanist", name = "accompanist-permissions", version.ref = "accompanistPermissions" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

---

### 🧩 Kotlin Language Features Used (and Why)

#### Coroutines — The Backbone of Everything Async

Every audio chunk, every API call, every UI update runs on coroutines. Here's the pattern used throughout this app:

```kotlin
// Services use a dedicated coroutine scope tied to the service lifecycle
class AudioCaptureService : LifecycleService() {

    // This scope is cancelled automatically when service is destroyed
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startCapture() {
        serviceScope.launch {
            // Audio capture loop runs on Default (CPU-bound) dispatcher
            while (isActive) {
                val chunk = readAudioChunk()         // blocking, fine on Default

                // Switch to IO for network call
                val transcript = withContext(Dispatchers.IO) {
                    whisperClient.transcribe(chunk)
                }

                val verdict = withContext(Dispatchers.IO) {
                    scamAnalyzer.analyze(transcript)
                }

                // Switch to Main to update UI
                withContext(Dispatchers.Main) {
                    overlayManager.updateVerdict(verdict)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()   // cleans up all running coroutines
    }
}
```

#### Flow — For Reactive UI Updates

Use `StateFlow` to push verdict updates from the service to the overlay:

```kotlin
// In your OverlayManager or a shared ViewModel
object CallAnalysisState {
    private val _verdict = MutableStateFlow<ScamVerdict>(ScamVerdict.ANALYZING)
    val verdict: StateFlow<ScamVerdict> = _verdict.asStateFlow()

    fun updateVerdict(verdict: ScamVerdict) {
        _verdict.value = verdict
    }
}

// In the overlay view, collect updates
serviceScope.launch {
    CallAnalysisState.verdict.collect { verdict ->
        withContext(Dispatchers.Main) {
            overlayView.updateVerdict(verdict)
        }
    }
}
```

#### Data Classes — For AI Response Models

```kotlin
// Clean, immutable response models
data class AnalysisResult(
    val score: Int,           // 0–100
    val verdict: ScamVerdict,
    val reasons: List<String>,
    val keywords: List<String>,
    val transcriptSegment: String
)

enum class ScamVerdict {
    ANALYZING,    // waiting for first result
    SAFE,         // score 0–30
    SUSPICIOUS,   // score 31–69
    SCAM          // score 70–100
}

// Map score to verdict cleanly with an extension function
fun Int.toVerdict(): ScamVerdict = when (this) {
    in 0..30   -> ScamVerdict.SAFE
    in 31..69  -> ScamVerdict.SUSPICIOUS
    else       -> ScamVerdict.SCAM
}
```

#### Sealed Classes — For Call State

```kotlin
sealed class CallState {
    object Idle : CallState()
    data class Ringing(val number: String) : CallState()
    data class Active(val number: String, val analyzeRequested: Boolean) : CallState()
    data class Ended(val number: String, val finalVerdict: ScamVerdict) : CallState()
}
```

#### Extension Functions — For Utilities

```kotlin
// AudioUtils.kt — attach WAV header to raw PCM bytes
fun ByteArray.toWav(sampleRate: Int = 16000): ByteArray {
    val totalDataLen = this.size + 36
    val byteRate = sampleRate * 2 // mono, 16-bit
    return ByteArray(44).also { header ->
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // ... full WAV header construction
    } + this
}

// PhoneUtils.kt — normalize number format
fun String.normalizePhoneNumber(): String =
    this.replace(Regex("[^+\\d]"), "")
```

---

### 🌐 Networking Layer — Retrofit Setup in Detail

#### API Interface Definitions

```kotlin
// api/WhisperApi.kt
interface WhisperApi {
    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody,
        @Part("response_format") format: RequestBody
    ): TranscriptionResponse
}

// api/GptApi.kt
interface GptApi {
    @POST("v1/chat/completions")
    suspend fun analyze(
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

// api/GeminiApi.kt  ← MVP recommendation (audio + analysis in one call)
interface GeminiApi {
    @POST("v1beta/models/gemini-1.5-flash:generateContent")
    suspend fun analyzeAudio(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}
```

#### Retrofit Instance (with OkHttp interceptors)

```kotlin
// di/NetworkModule.kt  (Hilt module)
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY  // remove in production
            })
            .addInterceptor { chain ->
                // Attach OpenAI API key to every request automatically
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                        .build()
                )
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)   // Whisper can be slow
            .build()

    @Provides
    @Singleton
    fun provideWhisperApi(okHttpClient: OkHttpClient): WhisperApi =
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WhisperApi::class.java)

    @Provides
    @Singleton
    fun provideGptApi(okHttpClient: OkHttpClient): GptApi =
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GptApi::class.java)
}
```

#### Storing API Keys Safely

Never hardcode API keys in source files. Use `local.properties`:

```properties
# local.properties  (this file is git-ignored by default — never commit it)
OPENAI_API_KEY=sk-...your-key...
GEMINI_API_KEY=AIza...your-key...
```

Then expose them via `BuildConfig` in `build.gradle.kts`:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        buildConfigField(
            "String",
            "OPENAI_API_KEY",
            "\"${properties["OPENAI_API_KEY"]}\""
        )
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${properties["GEMINI_API_KEY"]}\""
        )
    }
}
```

Then access in code: `BuildConfig.OPENAI_API_KEY`

---

### 🎨 UI Layer — Compose vs XML (When to Use Each)

This app uses **both**. Here's the clear rule:

| UI Location | Framework | Why |
|-------------|-----------|-----|
| Main app screens (setup, history, settings) | **Jetpack Compose** | Modern, less boilerplate |
| Floating call overlay | **XML + custom View** | `WindowManager` does not support Compose natively |
| Overlay animations (pulse, color change) | **ObjectAnimator (XML)** | More control, no Compose runtime overhead |

#### Compose — Navigation Setup

```kotlin
// ui/navigation/AppNavigation.kt
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "setup"
    ) {
        composable("setup")   { SetupScreen(navController) }
        composable("home")    { HomeScreen(navController) }
        composable("history") { HistoryScreen(navController) }
        composable("settings"){ SettingsScreen(navController) }
    }
}
```

#### Compose — ViewModel Pattern for Screens

```kotlin
// ui/screens/history/HistoryViewModel.kt
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository
) : ViewModel() {

    val callLogs: StateFlow<List<CallLogEntity>> =
        callLogRepository.getAllLogs()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
}

// ui/screens/history/HistoryScreen.kt
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val callLogs by viewModel.callLogs.collectAsStateWithLifecycle()

    LazyColumn {
        items(callLogs) { log ->
            CallLogItem(log = log)
        }
    }
}
```

#### XML Overlay View — Permission Check Before Showing

```kotlin
// ui/overlay/OverlayManager.kt
class OverlayManager(private val context: Context) {

    private var overlayView: View? = null
    private val windowManager = context.getSystemService(WindowManager::class.java)

    fun show(callerNumber: String) {
        // CRITICAL: always check permission before attempting to add window
        if (!Settings.canDrawOverlays(context)) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,  // API 26+
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120  // px from top
        }

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_call, null).also {
            it.findViewById<TextView>(R.id.tv_number).text = callerNumber
            it.findViewById<Button>(R.id.btn_yes).setOnClickListener { onYesClicked() }
            it.findViewById<Button>(R.id.btn_no).setOnClickListener { dismiss() }
            windowManager.addView(it, params)
        }
    }

    fun updateVerdict(verdict: ScamVerdict) {
        overlayView?.let { view ->
            val color = when (verdict) {
                ScamVerdict.SAFE       -> Color.parseColor("#4CAF50")
                ScamVerdict.SUSPICIOUS -> Color.parseColor("#FF9800")
                ScamVerdict.SCAM       -> Color.parseColor("#F44336")
                ScamVerdict.ANALYZING  -> Color.parseColor("#2196F3")
            }
            view.findViewById<TextView>(R.id.tv_verdict)?.apply {
                text = verdict.name
                setTextColor(color)
            }
        }
    }

    fun dismiss() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}
```

---

### 🗄️ Data Layer — Room Database Setup

```kotlin
// data/db/CallLogEntity.kt
@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val callTimestamp: Long,          // Unix millis
    val durationSeconds: Int,
    val finalVerdict: String,         // "SAFE" | "SUSPICIOUS" | "SCAM"
    val finalScore: Int,              // 0–100
    val fullTranscript: String,
    val detectedKeywords: String,     // JSON array stored as string
    val wasBlocked: Boolean = false
)

// data/db/CallLogDao.kt
@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY callTimestamp DESC")
    fun getAllLogs(): Flow<List<CallLogEntity>>   // Flow = live updates

    @Query("SELECT * FROM call_logs WHERE phoneNumber = :number")
    suspend fun getLogsForNumber(number: String): List<CallLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query("SELECT COUNT(*) FROM call_logs WHERE finalVerdict = 'SCAM'")
    suspend fun getScamCount(): Int
}

// data/db/AppDatabase.kt
@Database(entities = [CallLogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "scam_detector_db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
```

---

### 🤖 AI / Backend Services — Full Detail

#### Option A: OpenAI (Two-Step — Higher Accuracy)

| Service | Model | Cost | Latency |
|---------|-------|------|---------|
| **Whisper** | `whisper-1` | $0.006 / minute of audio | ~1–2 sec per 3s chunk |
| **GPT-4o mini** | `gpt-4o-mini` | ~$0.002 per analysis call | ~0.5–1 sec |
| **Total per 10-min call** | — | ~$0.08–0.12 | ~4–6 sec lag |

```kotlin
// ai/WhisperClient.kt
class WhisperClient @Inject constructor(private val whisperApi: WhisperApi) {

    suspend fun transcribe(audioBytes: ByteArray): String {
        val wavBytes = audioBytes.toWav()
        val requestFile = wavBytes.toRequestBody("audio/wav".toMediaType())
        val audioPart = MultipartBody.Part.createFormData("file", "chunk.wav", requestFile)
        val model = "whisper-1".toRequestBody("text/plain".toMediaType())
        val language = "az".toRequestBody("text/plain".toMediaType()) // Azerbaijani + auto-detects English
        val format = "text".toRequestBody("text/plain".toMediaType())

        return whisperApi.transcribeAudio(audioPart, model, language, format).text
    }
}

// ai/ScamAnalyzer.kt
class ScamAnalyzer @Inject constructor(private val gptApi: GptApi) {

    private val systemPrompt = """
        You are a real-time scam call detector. You receive short transcript 
        segments from an ongoing phone call. Analyze for scam indicators and 
        respond ONLY with a valid JSON object — no explanation, no markdown:
        {
          "score": <0-100>,
          "verdict": "<SAFE|SUSPICIOUS|SCAM>",
          "reasons": ["<reason1>", "<reason2>"],
          "keywords": ["<flagged word>"]
        }
        Score guide: 0-30=SAFE, 31-69=SUSPICIOUS, 70-100=SCAM.
        Be aggressive in flagging urgency, payment requests, impersonation, 
        and personal info requests. Accumulate context across segments.
    """.trimIndent()

    suspend fun analyze(transcript: String): AnalysisResult {
        val request = ChatCompletionRequest(
            model = "gpt-4o-mini",
            messages = listOf(
                Message(role = "system", content = systemPrompt),
                Message(role = "user", content = "Transcript segment: \"$transcript\"")
            ),
            temperature = 0.1,    // low = more consistent, less creative
            maxTokens = 200
        )
        val response = gptApi.analyze(request)
        return parseAnalysisResult(response.choices.first().message.content)
    }
}
```

#### Option B: Gemini 1.5 Flash (MVP Recommended — Audio Direct)

```kotlin
// ai/GeminiAudioAnalyzer.kt
class GeminiAudioAnalyzer @Inject constructor(private val geminiApi: GeminiApi) {

    // Send raw audio bytes directly — no Whisper needed
    suspend fun analyzeAudioChunk(audioBytes: ByteArray): AnalysisResult {
        val base64Audio = Base64.encodeToString(audioBytes.toWav(), Base64.NO_WRAP)

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(
                            inlineData = InlineData(
                                mimeType = "audio/wav",
                                data = base64Audio
                            )
                        ),
                        GeminiPart(
                            text = """
                                This is a segment of a phone call from an unknown number.
                                Listen carefully and detect if this is a scam call.
                                Look for: urgency tactics, payment requests (gift cards, wire transfer, crypto),
                                impersonation (bank, police, IRS, tech support), personal info fishing,
                                prize or lottery claims.
                                
                                Respond ONLY with JSON — no explanation:
                                {
                                  "score": <0-100>,
                                  "verdict": "<SAFE|SUSPICIOUS|SCAM>",
                                  "reasons": ["reason1"],
                                  "keywords": ["flagged phrase"],
                                  "transcript": "<what was said>"
                                }
                            """.trimIndent()
                        )
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.1f,
                maxOutputTokens = 300
            )
        )

        val response = geminiApi.analyzeAudio(BuildConfig.GEMINI_API_KEY, request)
        return parseGeminiResult(response)
    }
}
```

| | OpenAI (Whisper + GPT) | Gemini 1.5 Flash |
|--|------------------------|------------------|
| **API calls per chunk** | 2 | 1 |
| **Setup complexity** | Medium | Low |
| **Azerbaijani support** | ✅ Whisper handles it well | ✅ Gemini multilingual |
| **Audio → insight latency** | ~2–4 sec | ~1–3 sec |
| **Cost per 10 min call** | ~$0.10 | ~$0.05 |
| **MVP recommendation** | ⬜ | ✅ |

---

### 🔔 Foreground Service — Keeping Audio Alive

Android will kill background services aggressively. The audio capture **must** run as a foreground service with a persistent notification:

```kotlin
// services/AudioCaptureService.kt
class AudioCaptureService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "scam_detector_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_CAPTURE"
        const val ACTION_STOP = "ACTION_STOP_CAPTURE"
        const val EXTRA_NUMBER = "EXTRA_PHONE_NUMBER"

        fun startIntent(context: Context, number: String) =
            Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NUMBER, number)
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startCapture(intent.getStringExtra(EXTRA_NUMBER) ?: "Unknown")
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY   // don't restart if killed — call is over
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("🛡️ Scam Shield Active")
        .setContentText("Analyzing call in real-time...")
        .setSmallIcon(R.drawable.ic_shield)
        .setOngoing(true)           // can't be swiped away
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scam Detection",
            NotificationManager.IMPORTANCE_LOW    // silent — no sound/vibration
        ).apply {
            description = "Active while analyzing a phone call"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
```

---

## Android APIs Used

### 1. `CallScreeningService`
- **What it does:** Intercepts incoming calls before the phone rings
- **What you use it for:** Check if number is unsaved, trigger overlay
- **User setup required:** User must go to Phone app → Caller ID & Spam → select your app (one time)

```kotlin
class ScamScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        // Check contacts, trigger overlay, allow call through
    }
}
```

### 2. `InCallService`
- **What it does:** Your app becomes a "phone companion" during active calls
- **What you use it for:** Switch to speakerphone, detect call state changes
- **User setup required:** User must grant permission when prompted

```kotlin
class ScamInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        // Monitor state, switch to speaker when active
    }
}
```

### 3. `AudioRecord` with `VOICE_COMMUNICATION` Source
- **What it does:** Records from the microphone
- **Source type:** `MediaRecorder.AudioSource.VOICE_COMMUNICATION` is optimized for call audio
- **Why it works:** When speaker is on, the earpiece audio bleeds into the mic physically

```kotlin
AudioRecord(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    16000, // 16kHz — enough for speech recognition
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)
```

### 4. `SYSTEM_ALERT_WINDOW` (Overlay Permission)
- **What it does:** Draw floating views on top of all other apps (including the call screen)
- **What you use it for:** Consent prompt + live verdict display
- **User setup required:** User must grant "Display over other apps" in settings (your app prompts them)

---

## Audio Pipeline

```
Microphone (both voices via speakerphone)
        │
        ▼
AudioRecord.read() — raw PCM bytes
        │
        ▼  every 3 seconds
Buffer chunk (16kHz, mono, PCM16)
        │
        ▼
Convert to WAV (add header)
        │
        ├──── Option A: Send to Whisper API → get text → send text to GPT-4o mini
        │
        └──── Option B: Send raw audio to Gemini 1.5 Flash → get text + analysis in one shot
                              │
                              ▼
                    Scam verdict + reasons
                              │
                              ▼
                    Update overlay UI on main thread
```

### Chunk Strategy

| Setting | Value | Why |
|---------|-------|-----|
| Sample rate | 16,000 Hz | Whisper/Gemini minimum for good accuracy |
| Channels | Mono | Sufficient, halves data size |
| Encoding | PCM 16-bit | Standard |
| Chunk duration | 3 seconds | Balance between latency and accuracy |
| Overlap | 0.5 sec | Prevents cutting words at boundaries |

---

## AI Analysis Pipeline

### Whisper + GPT-4o mini (Two-step)

```
Step 1 — Transcription:
POST https://api.openai.com/v1/audio/transcriptions
Body: { file: audio.wav, model: "whisper-1", language: "az" or "en" }
Response: { text: "Your bank account has been locked..." }

Step 2 — Analysis:
POST https://api.openai.com/v1/chat/completions
Model: gpt-4o-mini
System prompt:
  "You are a scam call detector. Analyze the transcript segment
   and return JSON with:
   - score: 0-100 (scam likelihood)
   - verdict: SAFE | SUSPICIOUS | SCAM
   - reasons: [list of red flags found]
   - keywords: [flagged words/phrases]
   Be concise. This is real-time."

User: "Transcript: [text here]"
```

### Scam Detection Patterns (LLM Prompt Should Cover)

| Red Flag | Examples |
|----------|---------|
| **Urgency / threats** | "Account will be closed", "act now", "last warning" |
| **Payment requests** | "gift cards", "wire transfer", "crypto", "iTunes" |
| **Impersonation** | "This is the IRS", "police", "bank security" |
| **Personal info fishing** | "confirm your SSN", "verify card number" |
| **Prize/lottery scams** | "you've won", "claim your prize" |
| **Tech support scams** | "your computer is infected", "remote access" |

---

## Data Flow Diagram

```
PHONE CALL STARTS
      │
      ├─── CallScreeningService
      │         └─── Is number in contacts?
      │                   ├── YES → do nothing
      │                   └── NO  → show overlay
      │
      ├─── User taps YES on overlay
      │
      ├─── InCallService → setAudioRoute(SPEAKER)
      │
      ├─── AudioCaptureService starts
      │         └─── AudioRecord loop (3s chunks)
      │                   └─── WAV chunk created
      │                             └─── POST to Whisper/Gemini
      │                                       └─── transcript received
      │                                                 └─── POST to GPT-4o
      │                                                           └─── verdict JSON
      │                                                                     └─── update overlay
      │
      └─── CALL ENDS
                └─── AudioRecord stops
                └─── Full transcript assembled
                └─── Final verdict calculated
                └─── Saved to Room DB
                └─── Summary screen shown
```

---

## Permissions Breakdown

```xml
<!-- AndroidManifest.xml -->

<!-- Core telephony -->
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.READ_CALL_LOG"/>
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS"/>

<!-- Audio capture -->
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<!-- Overlay UI -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>

<!-- Keep service alive during call -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>

<!-- Check contacts -->
<uses-permission android:name="android.permission.READ_CONTACTS"/>

<!-- Network (for AI API calls) -->
<uses-permission android:name="android.permission.INTERNET"/>
```

### Permission Request Flow in App

```
First launch:
1. RECORD_AUDIO          → runtime request (required)
2. READ_CONTACTS         → runtime request (required)
3. READ_PHONE_STATE      → runtime request (required)
4. SYSTEM_ALERT_WINDOW   → redirect to Settings (special permission)
5. CallScreeningService  → redirect to Phone app settings (one-time)
```

---

## Project Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   │
│   ├── kotlin/com/yourapp/scamdetector/
│   │   │
│   │   ├── services/
│   │   │   ├── ScamScreeningService.kt      ← detects unsaved calls
│   │   │   ├── ScamInCallService.kt         ← manages active call, speaker
│   │   │   └── AudioCaptureService.kt       ← records + chunks audio
│   │   │
│   │   ├── ui/
│   │   │   ├── overlay/
│   │   │   │   ├── OverlayManager.kt        ← shows/hides/updates overlay
│   │   │   │   └── CallOverlayView.kt       ← XML-based floating view
│   │   │   │
│   │   │   └── screens/
│   │   │       ├── MainActivity.kt          ← setup + permissions
│   │   │       ├── SetupScreen.kt           ← onboarding (Compose)
│   │   │       └── HistoryScreen.kt         ← past calls + verdicts (Compose)
│   │   │
│   │   ├── ai/
│   │   │   ├── WhisperClient.kt             ← audio → transcript
│   │   │   ├── ScamAnalyzer.kt              ← transcript → verdict
│   │   │   └── models/
│   │   │       ├── AnalysisResult.kt        ← data class: score, verdict, reasons
│   │   │       └── ScamVerdict.kt           ← enum: SAFE, SUSPICIOUS, SCAM
│   │   │
│   │   ├── data/
│   │   │   ├── db/
│   │   │   │   ├── AppDatabase.kt           ← Room setup
│   │   │   │   ├── CallLogDao.kt            ← DB queries
│   │   │   │   └── CallLogEntity.kt         ← DB entity
│   │   │   └── ContactsHelper.kt            ← check if number is saved
│   │   │
│   │   └── utils/
│   │       ├── AudioUtils.kt                ← PCM → WAV conversion
│   │       └── PhoneUtils.kt                ← number formatting helpers
│   │
│   └── res/
│       ├── layout/
│       │   └── overlay_call.xml             ← floating overlay layout
│       └── ...
│
└── build.gradle.kts
```

---

## Build Order (Phase by Phase)

### Phase 1 — Foundation (Week 1)
```
✅ Create Android project (Empty Activity, Kotlin, API 29+)
✅ Add all permissions to manifest
✅ Build permission request flow in MainActivity
✅ Build ContactsHelper (check if number is saved)
✅ Test: permission grants work correctly
```

### Phase 2 — Call Detection (Week 1-2)
```
✅ Implement ScamScreeningService
✅ Register service in manifest with correct intent-filter
✅ Test: service fires on incoming calls
✅ Test: correctly identifies unsaved numbers
✅ Guide user to set app in Phone settings (Caller ID)
```

### Phase 3 — Overlay UI (Week 2)
```
✅ Build CallOverlayView (XML layout: consent prompt)
✅ Build OverlayManager (WindowManager add/remove/update)
✅ Wire overlay to ScamScreeningService
✅ Test: overlay appears over call screen
✅ Test: YES/NO buttons work
```

### Phase 4 — Audio Capture (Week 2-3)
```
✅ Implement ScamInCallService
✅ Implement setAudioRoute(SPEAKER) on call active
✅ Build AudioCaptureService with AudioRecord
✅ Save chunks to local files first (no AI yet)
✅ Test: audio files captured correctly, both voices audible
```

### Phase 5 — AI Integration (Week 3-4)
```
✅ Add Retrofit + OkHttp dependencies
✅ Build WhisperClient (upload WAV, get transcript)
✅ Build ScamAnalyzer (send transcript to GPT-4o mini)
✅ Define scam detection prompt
✅ Test: transcript accuracy on sample calls
✅ Test: scam verdicts on known scam transcripts
```

### Phase 6 — Live Verdict UI (Week 4)
```
✅ Update CallOverlayView with verdict display states
✅ Wire AnalysisResult to overlay in real-time
✅ Add vibration on SCAM verdict
✅ Test: overlay updates smoothly during call
```

### Phase 7 — Polish & Storage (Week 5)
```
✅ Room DB for call logs
✅ History screen (Compose)
✅ End-of-call summary screen
✅ Block number option
✅ Edge case handling (call dropped, network failure)
```

---

## OEM Compatibility Notes

| Manufacturer | Status | Notes |
|--------------|--------|-------|
| **Google Pixel** | ✅ Best | Pure Android, all APIs work perfectly |
| **Samsung (One UI)** | ✅ Good | Minor delay on speaker switch, test thoroughly |
| **Xiaomi (MIUI/HyperOS)** | ⚠️ Test carefully | Background service may be killed aggressively |
| **OnePlus (OxygenOS)** | ✅ Good | Generally well-behaved |
| **Huawei (EMUI)** | ⚠️ Difficult | No Google services on recent models |
| **Oppo / Realme** | ⚠️ Test carefully | MIUI-derived restrictions |

### Xiaomi / MIUI Fix
```kotlin
// In your foreground service, add notification early
// Also guide user to: Settings → Apps → Your App
// → Battery Saver → No restrictions
// → Autostart → Enable
```

---

## Play Store Considerations

### What Will Be Reviewed Carefully

| Permission | Play Store Scrutiny Level | Mitigation |
|------------|--------------------------|------------|
| `READ_CALL_LOG` | 🔴 High — requires declaration | Write clear privacy policy, explain scam protection use case |
| `RECORD_AUDIO` | 🟡 Medium | Show consent screen every time, never record silently |
| `SYSTEM_ALERT_WINDOW` | 🟡 Medium | Normal for call-related apps |

### Privacy Policy Must Include
- What audio data is collected
- That audio is sent to third-party AI APIs (OpenAI/Google)
- How long transcripts are stored
- How users can delete their data
- That recording only happens with explicit user consent per call

---

## Known Limitations

| Limitation | Severity | Notes |
|------------|----------|-------|
| **Speakerphone required** | Medium | User is aware of it; can be framed as a feature |
| **Audio quality varies** | Low-Medium | Noisy environments reduce accuracy |
| **API costs** | Low | Whisper: ~$0.006/min, GPT-4o mini: cheap. 10 min call ≈ $0.10 |
| **3-second latency** | Low | Verdict lags 3-6 seconds behind conversation |
| **MIUI background kill** | Medium | Mitigatable with user guidance |
| **No call audio API** | Structural | Speakerphone trick is the only rootless solution |
| **VoIP calls (WhatsApp, etc.)** | N/A | Completely different — those apps would need separate integration |

---

## Recommended MVP Scope

For your **first working version**, focus only on:

```
✅ CallScreeningService (detect unsaved numbers)
✅ Overlay with YES/NO consent
✅ Auto-speakerphone on YES
✅ AudioRecord capture
✅ Gemini 1.5 Flash (audio + analysis in one call — simplest MVP path)
✅ Live overlay: SAFE / SUSPICIOUS / SCAM
```

Leave for later:
```
⏳ Call history / Room DB
⏳ Block number feature
⏳ Scam reporting / community database
⏳ Multi-language support
⏳ Custom sensitivity settings
```

---

*Built with Kotlin + Android Studio | Min SDK 29 (Android 10) | Target SDK 34*
