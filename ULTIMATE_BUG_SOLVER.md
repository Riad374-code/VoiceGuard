# ULTIMATE_BUG_SOLVER – VoiceGuard One-Shot Build Prompt

> Give this whole file to your AI coding assistant as a single prompt.

---

## ROLE

You are a senior Android engineer. Build the complete **VoiceGuard** Android app in **Kotlin**, following the spec below. Produce all files, full code, Gradle config, manifest, and a short README. Do not skip parts or leave TODOs. Ask no questions; where something is ambiguous, choose the safest option and document it in the README.

---

## 0. PRODUCT GOAL

VoiceGuard listens to the audio of a phone call, transcribes it in real time (Deepgram streaming STT), and sends the transcript text to the Groq API to score scam/fraud risk. The UI shows a live transcript, a risk % and a verdict.

---

## 1. HARD CONSTRAINT (design around it, do NOT try to bypass it)

On Android 10+, while a cellular (GSM/VoLTE) call is active, third-party apps cannot record the call audio:

- `VOICE_CALL` / `VOICE_DOWNLINK` / `VOICE_UPLINK` need `CAPTURE_AUDIO_OUTPUT` (system-only).
- On **Honor X8b (MagicOS)** and **Redmi Note 12 (MIUI)**, `MIC`, `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `UNPROCESSED` all return digital zeros during a cellular call, even with speakerphone on.
- Do **NOT** use root, hidden APIs, accessibility-service audio hacks, or anything that violates Google Play policy.

Therefore the app must support these input modes, selectable at runtime:

| Mode | Name | Description |
|------|------|-------------|
| **A** | **LISTENER MODE** (primary, real-time, works on Honor X8b) | The Honor X8b (or any phone) runs VoiceGuard and listens via its own microphone to a DIFFERENT phone's speaker that is on the call (speakerphone, volume high). The device running VoiceGuard is NOT in the call, so its mic is not blocked. Also works with a second phone placed near the call phone. |
| **B** | **FILE MODE** | User picks an audio file (call recording from the OEM dialer: m4a/mp3/wav/amr/3gp). App decodes it to 16 kHz mono PCM16 and streams it to the same pipeline (chunked, simulated real-time or faster). |
| **C** | **IN-APP VOIP** (optional, feature flag, default OFF) | Stub interface `RemoteAudioSource` so a WebRTC/SIP remote stream can be plugged in later. Implement the interface only, plus a README note. |
| **D** | **DEMO SIMULATION** | Scripted transcripts fed into the pipeline. Must be clearly labeled **"SIMULATION – NOT LIVE AUDIO"** with a persistent banner in the UI, and results must be flagged `source=SIMULATION`. |

When the app runs in Mode A on a device that **IS currently in a cellular call**, detect this (`TelephonyManager` / `PhoneStateListener` or `TelephonyCallback`, needs `READ_PHONE_STATE`) and show:

> "This phone is in a call. Its microphone is blocked by the OS. Use a second phone in Listener Mode or import a recording."

---

## 2. TECH STACK

- Kotlin, `minSdk 26`, `targetSdk 34`, `compileSdk 34`
- Jetpack Compose (Material 3), single-activity, MVVM + StateFlow, **manual DI** (keep it simple)
- Coroutines + Flow
- OkHttp 4 (WebSocket for Deepgram, REST for Groq), kotlinx.serialization for JSON
- DataStore (Preferences) for settings
- Timber for logging
- **No API keys hardcoded**: read from `local.properties` → `BuildConfig` fields (`DEEPGRAM_API_KEY`, `GROQ_API_KEY`) AND allow overriding in an in-app Settings screen (stored in `EncryptedSharedPreferences`). Add a README warning that production must proxy keys through a backend.

---

## 3. ARCHITECTURE (packages)

```
com.voiceguard
 ├─ audio/      AudioCaptureEngine, PcmChunk, AudioHealthAnalyzer, VadGate, FileAudioDecoder, RemoteAudioSource (interface)
 ├─ stt/        DeepgramStreamingClient, TranscriptEvent, TranscriptAggregator
 ├─ ai/         GroqRiskScorer, RiskVerdict, PromptBuilder
 ├─ pipeline/   PipelineOrchestrator, PipelineState, PipelineDiagnostics
 ├─ service/    ListeningForegroundService
 ├─ telephony/  CallStateMonitor
 ├─ demo/       DemoScriptRunner
 ├─ data/       SettingsRepository, HistoryRepository (Room optional: store sessions + verdicts)
 └─ ui/         MainScreen, DiagnosticsScreen, SettingsScreen, HistoryScreen, ViewModels, theme
```

---

## 4. STEP-BY-STEP IMPLEMENTATION REQUIREMENTS

### STEP 1 – Project setup

- Gradle Kotlin DSL, version catalog.
- Dependencies: Compose BOM, material3, lifecycle-runtime-compose, lifecycle-service, okhttp, kotlinx-serialization-json, datastore-preferences, security-crypto, timber, room (optional). Use framework `MediaExtractor`/`MediaCodec` for decoding (or media3-extractor).
- `BuildConfig` fields from `local.properties` (fallback to empty string).

### STEP 2 – Manifest & permissions

- `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (API 33+), `READ_PHONE_STATE`, `WAKE_LOCK`.
- Service declared with `android:foregroundServiceType="microphone"`.
- Runtime permission flow in Compose: explain why each permission is needed **before** requesting; handle "denied permanently" by opening app settings.
- **Android 14**: start the foreground service ONLY from a visible Activity user action (button tap). Never start it from the background. Call `startForeground` within 5 seconds with a proper notification channel.

### STEP 3 – AudioCaptureEngine

- Use `AudioRecord`, **16000 Hz, mono, PCM_16BIT**. Buffer = `max(minBufferSize*2, 3200 bytes)`. Read in **100 ms chunks (3200 bytes)** on a dedicated `Dispatchers.IO` coroutine, emit `PcmChunk(bytes, timestampMs)` via a SharedFlow/Channel.
- Source priority for Mode A: `VOICE_RECOGNITION` → `MIC` → `UNPROCESSED` (try the next if init fails). **Never use `VOICE_COMMUNICATION`** with AEC/NS for Listener Mode, because echo cancellation can erase the speaker audio. If `AcousticEchoCanceler` / `NoiseSuppressor` / `AutomaticGainControl` are available, explicitly **DISABLE AEC and NS**, and optionally enable AGC.
- Log the actual source, sample rate, and audio state at start.
- Handle `AudioRecord.ERROR`, restart logic with backoff, and release everything on stop.
- Do **not** request audio focus (we only record). Register `AudioManager.AudioRecordingCallback` and read `isClientSilenced` (API 29+); log/report it. This is a key signal that the OS is silencing us.

### STEP 4 – AudioHealthAnalyzer (critical diagnostic)

Every **1-second window** compute:

- `maxAbs`, RMS (dBFS), `nonZeroRatio`, `zeroCrossingRate`
- `isDigitalSilence` (`maxAbs == 0` or `nonZeroRatio < 0.001`)
- `isClipping` (>1% samples at ±32767)
- `isSilencedByOS` (from `AudioRecordingCallback`)

Classify into: `OK_SPEECH_LIKELY`, `QUIET_ROOM` (small noise floor, not zero), `DIGITAL_SILENCE` (OS blocked / mic muted), `NOISE_ONLY` (high RMS, low speech-band energy), `CLIPPING`.

- **Speech-band heuristic**: energy in 300–3400 Hz vs total using a cheap biquad/bandpass or a small FFT; `NOISE_ONLY` if RMS is high but speech-band ratio is low.
- Emit `AudioHealth` StateFlow.
- If `DIGITAL_SILENCE` lasts ≥ 3 seconds, raise `PipelineState.BlockedByOs` and show the UI message:

> "Microphone is returning silence. Likely blocked by the OS during a call, or muted by the device. Try Listener Mode on a second phone or File Mode."

### STEP 5 – VadGate

- Energy + speech-band VAD with hangover (300 ms pre-roll, 500 ms hangover). Keep a ring buffer of the last 300 ms so speech onset is not clipped.
- Only forward chunks to Deepgram while VAD is active (plus the pre-roll). While idle, send Deepgram KeepAlive JSON `{"type":"KeepAlive"}` every 5 seconds instead of audio. This stops billing for silence.
- Thresholds configurable in Settings (sensitivity slider), with **adaptive noise floor** estimation (track min RMS over 3 s and set threshold = floor + margin).
- **Never forward when the analyzer says `DIGITAL_SILENCE`.**

### STEP 6 – DeepgramStreamingClient

- OkHttp WebSocket to:
  `wss://api.deepgram.com/v1/listen?model=<model>&language=<lang>&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&smart_format=true&punctuate=true&endpointing=300&utterance_end_ms=1000&vad_events=true`
- Header: `Authorization: Token <key>`.
- Settings: model (default `nova-2`; allow `nova-3`), language (default `"multi"` if the model supports it, else selectable: en, az, tr, ru, hi, ur, bn, etc.; list configurable), and an "auto-detect" toggle. A wrong language returns blank transcripts, so surface a UI hint: *"No words recognized – check language setting."*
- Send binary frames of the PCM chunks. On stop, send `{"type":"CloseStream"}`.
- Parse `Results` messages: `channel.alternatives[0].transcript`, `is_final`, `speech_final`, `confidence`, and `metadata.request_id`. Emit `TranscriptEvent(text, isFinal, confidence, tsMs)`.
- Auto-reconnect with exponential backoff (1 s, 2 s, 4 s … max 15 s), buffering up to 3 seconds of audio during reconnect. Handle **401/403** (invalid key) with a clear UI error, not a silent retry loop.
- Track counters: `audioBytesSent`, `secondsSent`, `messagesReceived`, `emptyTranscriptCount`, `wordsReceived`. If `secondsSent > 20` and `wordsReceived == 0` while VAD was active, raise a `NoWordsWarning` with likely causes (wrong language / noise only / audio too quiet).
- Keep the connection lifecycle tied to the **session**, not to the Activity.

### STEP 7 – TranscriptAggregator

- Maintain: finalized text segments, current interim text, rolling window text (last ~60 words), word count.
- Trigger scoring (emit `ScoreRequest`) when:
  - (a) a final segment arrives and the rolling window ≥ 4 words, or
  - (b) every 8 seconds if there are ≥ 4 new words since the last score, or
  - (c) an utterance_end event.
- `MIN_WORDS` configurable (default 4) and `DEBOUNCE` configurable.
- Dedupe repeated finals; keep speaker turns if diarize is enabled (optional flag `diarize=true` in the Deepgram query).

### STEP 8 – GroqRiskScorer

- `POST https://api.groq.com/openai/v1/chat/completions` with `Authorization: Bearer <key>`, model configurable (default `llama-3.3-70b-versatile`), `temperature 0`, `response_format json_object`, `max_tokens 300`, timeout 15 s, 2 retries with backoff, handle **429** using the `Retry-After` header, and serialize requests (at most 1 in flight, latest-wins queue).
- **PromptBuilder**: system prompt = fraud/scam-call risk analyst. Input = rolling transcript (and previous risk for smoothing). Output JSON ONLY:

```json
{
  "risk": 0,
  "verdict": "SAFE|SUSPICIOUS|SCAM",
  "reasons": ["..."],
  "tactics": ["urgency", "OTP request", "impersonation of bank/police", "gift cards", "remote access"],
  "advice": "one short sentence"
}
```

- Parse defensively (strip code fences, validate ranges). Smooth the displayed risk (EMA with alpha 0.5, but jump immediately if risk ≥ 80).
- Language: the prompt must state that the transcript may be in any language (Azerbaijani, Turkish, Russian, English, etc.) and require the reasons in the UI language selected in Settings.
- Counters: `requestsSent`, `lastLatencyMs`, tokens in/out (from `usage`), `lastError`.

### STEP 9 – PipelineOrchestrator + PipelineState

State machine:

`Idle → Starting → Listening → (SpeechDetected | WaitingForSpeech) → Transcribing → Scoring → Result`

Error states: `PermissionMissing`, `BlockedByOs`, `PhoneInCall`, `NetworkError`, `DeepgramAuthError`, `GroqError`, `NoWords`.

- The orchestrator wires: **Capture → Analyzer → VadGate → Deepgram → Aggregator → Groq → UI**, exposing a single `UiState` StateFlow.
- Every stage exposes counters into `PipelineDiagnostics` (see Step 10).
- The UI must **NEVER** show a plain "0%" risk when nothing was scored. Show "Waiting for speech…" / "No audio detected" / "Blocked by OS" instead. Use `null` risk = "not scored yet".

### STEP 10 – Diagnostics screen (very important)

A live panel and exportable log (share as `.txt`) showing per stage:

- **Device**: model, Android version, manufacturer, ROM (MagicOS/MIUI detection via Build props), whether in a call.
- **Audio**: source used, sample rate, `isClientSilenced`, RMS dBFS, `maxAbs`, `nonZeroRatio`, classification, seconds of digital silence.
- **VAD**: active/idle, % time active.
- **Deepgram**: connection state, `request_id`, `secondsSent`, messages, words, empty results, last error, latency.
- **Groq**: requests, last latency, tokens in/out, last error.

A **Self-test** button that:

1. Records 3 seconds and reports the audio health verdict.
2. Plays a built-in test WAV through the speaker and records it via the mic to verify the speaker→mic loop.
3. Sends a known text to Groq to verify the key.
4. Opens a Deepgram socket and checks the auth handshake.

Show pass/fail per test with a human-readable reason.

### STEP 11 – ListeningForegroundService

- Owns the orchestrator for the session lifetime. Notification with a **Stop** action and live status text (e.g., "Listening – risk 12%").
- Partial wake lock while active. Bind/observe from the ViewModel via a singleton state holder.
- Handle the service being killed: `START_NOT_STICKY` (Android 14 blocks background microphone starts, so don't try to resurrect it).
- On stop: close the WebSocket, release `AudioRecord`, cancel coroutines, and save the session summary.

### STEP 12 – CallStateMonitor

- Use `TelephonyCallback` (API 31+) or `PhoneStateListener` (older). Expose `isInCall`.
- If `isInCall && mode == LISTENER`: show a blocking dialog explaining the limit and offering **"Switch to File Mode"** and **"How to use Listener Mode"** (an illustrated 3-step guide):
  1. Put the call on speaker at high volume on **Phone A**.
  2. Place **Phone B**, running VoiceGuard, 10–20 cm from Phone A's speaker.
  3. Start listening on Phone B.
- Add a **consent notice** on first launch: the user must confirm that they have the legal right/consent of the participants to analyze call audio in their jurisdiction. Store the acknowledgement.

### STEP 13 – Honor / Xiaomi OEM handling (device-specific)

Detect manufacturer (HONOR/HUAWEI/Xiaomi/Redmi) and show a one-time setup checklist screen with deep links (try intents, fall back to app settings):

- **Battery**: set the app to "No restrictions" / disable battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and, on Honor, *App launch → manage manually* (allow auto-launch, secondary launch, run in background).
- **Xiaomi**: Autostart + "No restrictions" battery saver + lock in recents.
- Allow notifications.
- Turn off "Do not disturb" interference.

Do not rely on overlay/popup windows or background activity starts (Honor instantly dismisses them and Android 14 blocks them). Use the persistent notification + in-app UI + heads-up notification (high-importance channel) for alerts. If risk ≥ 80, post a heads-up notification with vibration; **no background `startActivity`**.

### STEP 14 – File Mode (Mode B)

- Use `ActivityResultContracts.OpenDocument` for `audio/*`.
- **FileAudioDecoder**: `MediaExtractor` + `MediaCodec` → PCM16, resample to 16 kHz mono (linear or windowed-sinc resampler), downmix stereo. Stream in 100 ms chunks through the same pipeline (VAD may be bypassed for files).
- Option: **"Fast mode"** (send as fast as Deepgram accepts, ~4× real-time) vs **"Real-time mode"**.
- Progress bar and a final report: full transcript, risk timeline chart (simple Canvas line chart), top reasons.

### STEP 15 – Demo simulation (Mode D)

- `DemoScriptRunner` emits scripted transcript events (3 scenarios: normal call, bank-impersonation scam with OTP request, tech-support remote-access scam), in EN and AZ/TR/RU, at realistic pacing, into `TranscriptAggregator` → the **real** `GroqRiskScorer` (so AI scoring is real) OR an offline canned scorer if there's no network (toggle).
- Persistent red/orange banner: **"SIMULATION – NOT LIVE AUDIO"**. Marked in history and exports.

### STEP 16 – UI (Compose, Material 3)

- **Main screen**: mode selector (Listener / File / Demo), big Start/Stop button, live waveform/level meter, status chip (from `PipelineState`), live transcript (interim in gray, final in white), risk gauge (null-safe: "—" when not scored), verdict chip, reasons list, and a diagnostics button.
- **Settings**: API keys, Deepgram model/language, Groq model, VAD sensitivity, `MIN_WORDS`, UI language, diarization, theme.
- **History**: list of sessions with a transcript and verdict timeline; export.
- All strings in `strings.xml` with EN + AZ (+ TR/RU if easy).

### STEP 17 – Error handling & UX rules

- No silent failures: every error must be mapped to a user-readable message and a suggested action.
- Do not show the app as "dead": always show the stage where the pipeline is waiting ("Waiting for speech – audio level OK", "Audio is silent – blocked?", "Transcribing – 0 words so far, check language").
- Timber logs with a tag per stage; a ring-buffer log kept in memory (last 500 lines) for the exported diagnostics.

### STEP 18 – Tests

- Unit tests: `AudioHealthAnalyzer` (zeros, noise, sine tone, clipping), `VadGate` (hangover/pre-roll), `TranscriptAggregator` (word threshold and debounce), the Groq JSON parser (malformed inputs), Deepgram message parser (sample JSON with empty transcripts).
- A fake Deepgram WebSocket server using OkHttp `MockWebServer` for integration tests.

### STEP 19 – README

Include: setup (`local.properties` keys), how to use Listener Mode with the Honor X8b step by step, why the in-call mic is blocked (short technical explanation), the diagnostics guide ("blank transcript? check X, Y, Z"), legal/consent note, key-security note, and limitations.

---

## 5. ACCEPTANCE CRITERIA

1. On a device **NOT** in a call, speaking near the phone yields a live transcript within ~1 s and Groq requests appear (visible in Diagnostics).
2. With the mic returning zeros, the app shows "Blocked by OS / silence" within 3 seconds, sends **NO** audio to Deepgram, and never shows a bare 0% risk.
3. **Listener Mode**: Phone A (call on speaker) + Phone B (VoiceGuard) produces a transcript and risk scoring.
4. **File Mode** transcribes an imported recording end-to-end.
5. **Demo Mode** runs offline-capable with the clearly visible simulation banner.
6. Diagnostics export shows counters for every stage, and Self-test pinpoints the failing stage.
7. The build succeeds with `./gradlew assembleDebug`; there are no hardcoded keys; the app doesn't crash on permission denial or network loss.

---

## OUTPUT FORMAT

First the project tree, then every file in full with its path as a header, then the README. Be complete.

---

## USAGE NOTES (for you, not the AI)

- **Test order matters.** Build and verify File Mode and the Diagnostics Self-test first. They prove the Deepgram → Groq path with no OS restrictions, so if the AI's code breaks later, you know which stage is at fault.
- **The Honor X8b will show `DIGITAL_SILENCE` while it's in a call.** That is the intended result: the app reports the OS block instead of freezing at 0%. For a real live test, put the call on Phone A and run VoiceGuard on the Honor as Phone B.
- **Coding AIs often truncate long output.** If yours does, say "continue with the next files" and keep the same project tree, or ask for it in chunks by Step number (1–5, 6–10, 11–15, 16–19).
- **Call recording and analysis have legal consent requirements** that vary by country. The prompt includes a consent screen; mention this in your presentation.
