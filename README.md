# VoiceGuard (Android)

Listens to call audio, transcribes it live (Deepgram streaming STT), scores
scam/fraud risk (Groq chat-completions). Live transcript + risk % + verdict.

## Setup

1. Copy `local.properties.example` → `local.properties`, fill `DEEPGRAM_API_KEY`
   and `GROQ_API_KEY` (or put them in `.env`; `local.properties` wins).
2. Or enter keys in-app: Settings → API keys (stored in EncryptedSharedPreferences).
3. `./gradlew assembleDebug` (release: `assembleRelease`).

> **Key security:** keys shipped in the app can be extracted. Production must
> proxy Deepgram/Groq through your backend and keep keys server-side.

## Why the in-call mic is blocked (short version)

Android 10+ reserves `VOICE_CALL/UPLINK/DOWNLINK` for system apps
(`CAPTURE_AUDIO_OUTPUT`). On Honor X8b (MagicOS) and Redmi Note 12 (MIUI) the
remaining sources (`MIC`, `VOICE_RECOGNITION`, …) return digital zeros while a
GSM/VoLTE call is active on the same phone — even on speaker. No Play-safe API
can read the other party. Hence **Listener Mode**: run VoiceGuard on a phone
that is NOT in the call, 10–20 cm from the calling phone's speaker.

## Listener Mode (Honor X8b, step by step)

1. Phone A: start the call, speakerphone, volume high.
2. Phone B (VoiceGuard): grant mic + notification + phone-state permissions,
   open Device setup for Honor/Xiaomi battery steps.
3. Phone B: Listen → Start listening → transcript appears in ~1 s; Groq
   requests visible in Diagnostics.
4. If Phone B itself is in a call, the app refuses and explains why.

## Diagnostics guide (blank transcript? check X, Y, Z)

- **X — Audio class**: `DIGITAL_SILENCE` ≥ 3 s → OS-blocked mic (expected while
  this phone is in a call); nothing is sent to Deepgram. `NOISE_ONLY` → too far
  / wind / handling noise. `QUIET_ROOM` → speak louder / move closer.
- **Y — Deepgram**: `secondsSent > 20` with 0 words while VAD is active →
  wrong STT language (try auto-detect or the call's language) or audio too quiet.
  `AUTH_ERROR` → bad key. `request_id` proves audio reached Deepgram.
- **Z — Groq**: scorer needs ≥ 4 words (`MIN_WORDS`); `requests` stays 0 until
  then. 429s back off via `Retry-After`; 401/403 means bad key.
- Self-test pinpoints the failing stage; Share log exports everything as `.txt`.

## Choices made where the spec was ambiguous

- Manual DI via `ServiceLocator` (no Hilt); orchestrator is app-scoped so the UI
  stays live, the foreground service owns the listener session lifetime.
- History is file-based JSONL, not Room (append-only, tiny schema).
- File/demo sessions run in-process with their own wake lock; only Listener Mode
  uses the microphone foreground service.
- `UNPROCESSED` mic source only on API 29+ (guarded); `VOICE_COMMUNICATION`
  never used; AEC/NS disabled, AGC on.
- `language=multi` default; auto-detect toggle adds `detect_language=true`.
- UI strings EN + AZ; Groq reasons language follows the UI-language setting.
- `compileSdk 36` (spec said 34, but current Compose libraries require 35/36);
  `targetSdk` stays 34, `minSdk` 26.
- Package is `com.voiceguard` — uninstall any old `com.guardvoice` build first
  (different package + signature).

## Legal / consent

Call recording/analysis needs participant consent in many jurisdictions. The app
shows a consent notice on first launch and stores the acknowledgement — this
does not replace legal advice for your country.

## Limitations

- Same-phone cellular-call audio is unreachable by design (OS block).
- `RemoteAudioSource` (Mode C, WebRTC/SIP) is an interface only, behind the
  `voipEnabled` flag; plug your stream in and the pipeline accepts it unchanged.
- Demo Mode is labeled SIMULATION everywhere and flagged `source=SIMULATION`.
