package com.voiceguard.stt

enum class AudioSource { LIVE, FILE, SIMULATION }

data class TranscriptEvent(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float,
    val tsMs: Long,
    val source: AudioSource = AudioSource.LIVE
)

