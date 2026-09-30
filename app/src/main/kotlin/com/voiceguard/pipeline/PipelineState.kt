package com.voiceguard.pipeline

import android.os.Build
import com.voiceguard.ai.Verdict
import com.voiceguard.audio.AudioHealth
import com.voiceguard.stt.AudioSource
import com.voiceguard.stt.DeepgramConnState

enum class ListenMode { LISTENER, FILE, DEMO }

enum class Stage {
    IDLE, STARTING, WAITING_FOR_SPEECH, SPEECH_DETECTED, TRANSCRIBING, SCORING, RESULT
}

data class SelfTestResult(val name: String, val passed: Boolean, val detail: String)

data class UiState(
    val mode: ListenMode = ListenMode.LISTENER,
    val running: Boolean = false,
    val stage: Stage = Stage.IDLE,
    /** Always a full sentence; never a bare "0%" when nothing was scored. */
    val statusText: String = "Idle",
    val transcriptFull: String = "",
    val interim: String = "",
    /** Null = not scored yet; UI shows "—". */
    val risk: Int? = null,
    val verdict: Verdict = Verdict.UNKNOWN,
    val reasons: List<String> = emptyList(),
    val tactics: List<String> = emptyList(),
    val advice: String = "",
    val health: AudioHealth? = null,
    val vadActive: Boolean = false,
    val vadFraction: Float = 0f,
    val levelDb: Double = Double.NEGATIVE_INFINITY,
    val levels: List<Float> = emptyList(),
    val micSource: String = "none",
    val inCall: Boolean = false,
    val blockedByOs: Boolean = false,
    val silenceSeconds: Double = 0.0,
    val deepgramState: DeepgramConnState = DeepgramConnState.IDLE,
    val deepgramHint: String? = null,
    val noWordsWarning: String? = null,
    val groqPending: Boolean = false,
    val fileProgress: Float? = null,
    val fileName: String = "",
    val isSimulation: Boolean = false,
    val deepgram: DeepgramDiag = DeepgramDiag(),
    val groq: GroqDiag = GroqDiag(),
    val device: DeviceDiag = DeviceDiag(),
    val selfTest: List<SelfTestResult> = emptyList(),
    val selfTestRunning: Boolean = false
)

data class DeepgramDiag(
    val requestId: String = "",
    val secondsSent: Double = 0.0,
    val messages: Long = 0,
    val words: Long = 0,
    val emptyResults: Long = 0,
    val lastError: String? = null
)

data class GroqDiag(
    val requests: Long = 0,
    val lastLatencyMs: Long = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val lastError: String? = null
)

data class DeviceDiag(
    val manufacturer: String = Build.MANUFACTURER ?: "",
    val model: String = Build.MODEL ?: "",
    val android: String = Build.VERSION.RELEASE ?: "",
    val sdk: Int = Build.VERSION.SDK_INT,
    val rom: String = detectRom(),
    val inCall: Boolean = false
) {
    companion object {
        fun detectRom(): String {
            val m = (Build.MANUFACTURER ?: "").lowercase()
            val display = (Build.DISPLAY ?: "").lowercase()
            return when {
                "honor" in m || "honor" in display || "magicos" in display.replace(" ", "") -> "MagicOS"
                "huawei" in m || "emui" in display -> "EMUI"
                "xiaomi" in m || "redmi" in m || "poco" in m || "miui" in display -> "MIUI/HyperOS"
                "samsung" in m || "one ui" in display -> "One UI"
                else -> display.take(32).ifBlank { "stock" }
            }
        }
    }
}

