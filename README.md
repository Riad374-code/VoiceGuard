<p align="center">
  <img src="./app/src/main/res/drawable-nodpi/guardvoice_logo.png" alt="GuardVoice logo" width="220">
</p>

<h1 align="center">GuardVoice</h1>

<p align="center">
  A consent-first Android app for preparing real-time AI scam call protection.
</p>

## Demo

<p align="center">
  <img src="./Demo.gif" alt="GuardVoice app demo" width="320">
</p>

## Overview

GuardVoice is an Android prototype that reacts to incoming phone calls, asks for per-call consent, and can start speakerphone plus microphone capture for a future AI scam detector. The app guides users through the permissions required for call screening, displays a consent-focused call popup, and provides dashboard, summary, settings, account, and billing interfaces.

The long-term product goal is to transcribe approved calls, analyze conversations for scam indicators, and present a live risk verdict without recording before the user gives consent.

## Current Features

- Detects incoming phone calls through `CallScreeningService`
- Always allows calls through while showing a consent popup
- Starts speakerphone and microphone capture only after the user allows tracking
- Requests runtime phone-state, contacts, notification, and microphone permissions
- Guides users to grant overlay access and the Android Caller ID role
- Includes setup, dashboard, call popup, call summary, settings, account, and billing screens
- Provides Safe, Risky, and Scam presentation states
- Includes unit tests for call-screening decisions and account validation

> [!NOTE]
> GuardVoice is currently a prototype. Live audio transcription, AI scam analysis, persistent call history, and production billing/authentication are not implemented yet.

## Technology

- Kotlin 2.2
- Jetpack Compose and Material 3
- Android `CallScreeningService`
- Android SDK 36
- Minimum Android version: Android 10 (API 29)
- Java 17
- Gradle Kotlin DSL

## Getting Started

### Prerequisites

- Android Studio with Android SDK 36 installed
- JDK 17
- An Android 10+ device or emulator

### Build and Run

1. Clone the repository:

   ```bash
   git clone https://github.com/Riad374-code/VoiceGuard.git
   cd VoiceGuard
   ```

2. Open the project in Android Studio and allow Gradle to synchronize.

3. Run the `app` configuration on an Android 10+ device or emulator.

You can also build from the command line:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
./gradlew assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## Device Setup

On first launch, complete the setup checklist:

1. Grant microphone, phone state, contacts, and notification permissions.
2. Allow GuardVoice to display over other apps.
3. Set GuardVoice as the device's Caller ID and spam app when Android prompts you.

Call-screening behavior can vary by device manufacturer. Testing on a physical Android phone is recommended.

## Tests

Run the local unit tests with:

```powershell
.\gradlew.bat test
```

Or on macOS and Linux:

```bash
./gradlew test
```

## Project Structure

```text
app/src/main/
|-- AndroidManifest.xml
|-- kotlin/com/guardvoice/
|   |-- account/     # Account models and validation
|   |-- call/        # Incoming call screening
|   `-- ui/          # Compose app, screens, components, and theme
`-- res/
    |-- drawable/    # Popup and launch assets
    |-- layout/      # Call overlay layout
    `-- values/      # Strings and styles
```

## Planned Work

- Stream microphone chunks into the AI analysis pipeline
- Add speech-to-text processing
- Analyze transcripts for scam patterns
- Update live risk predictions during calls
- Store call summaries and prediction history locally
- Replace prototype account and billing state with production services

## Privacy Direction

GuardVoice is designed around explicit consent:

- Incoming calls are allowed normally unless the user explicitly starts tracking.
- Every incoming call is allowed rather than silently blocked.
- Audio access is intended to start only after the user approves tracking for that call.
- Future transcript and call-history storage should remain transparent and user-controlled.

For the detailed product and technical blueprint, see [`voice-guard-full-tech.md`](./voice-guard-full-tech.md).
