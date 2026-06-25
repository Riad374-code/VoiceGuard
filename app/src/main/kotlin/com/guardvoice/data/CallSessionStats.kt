package com.guardvoice.data

private const val SPEECH_SAMPLE_RATE_HZ = 16_000
private const val PCM_16_BYTES_PER_SAMPLE = 2
private const val SECONDS_PER_MINUTE = 60
private const val BYTES_PER_AUDIO_SECOND = SPEECH_SAMPLE_RATE_HZ * PCM_16_BYTES_PER_SAMPLE

fun audioDurationSeconds(audioBytes: Long): Int =
    (audioBytes.coerceAtLeast(0L) / BYTES_PER_AUDIO_SECOND).toInt()

fun audioDurationLabel(audioBytes: Long): String {
    val totalSeconds = audioDurationSeconds(audioBytes)
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%02d:%02d".format(minutes, seconds)
}
