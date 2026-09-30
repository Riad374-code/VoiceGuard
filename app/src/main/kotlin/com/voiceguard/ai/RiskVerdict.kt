package com.voiceguard.ai

import com.voiceguard.stt.AudioSource

enum class Verdict { SAFE, SUSPICIOUS, SCAM, UNKNOWN }

data class RiskVerdict(
    val risk: Int,
    val verdict: Verdict,
    val reasons: List<String>,
    val tactics: List<String>,
    val advice: String,
    val source: AudioSource = AudioSource.LIVE
) {
    companion object {
        fun unscored(source: AudioSource = AudioSource.LIVE) =
            RiskVerdict(-1, Verdict.UNKNOWN, emptyList(), emptyList(), "", source)
    }
}

