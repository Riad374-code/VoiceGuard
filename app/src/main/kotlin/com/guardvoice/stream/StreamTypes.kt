package com.guardvoice.stream

import com.guardvoice.data.CallVerdict

/**
 * Shared streaming types — mirrors backend/src/types.ts
 * Kept in Kotlin for type safety; JSON serialization is manual to avoid Gson dependency.
 */
data class AudioChunkPayload(
    val sessionId: String,
    val sequence: Int,
    val pcmBase64: String,
    val durationMs: Int,
    val timestamp: Long
)

data class TranscriptionChunk(
    val textDelta: String,
    val transcriptAccumulated: String,
    val isPartial: Boolean
)

data class AnalysisUpdate(
    val windowIndex: Int,
    val windowStartSec: Int,
    val windowEndSec: Int,
    val transcriptWindow: String,
    val windowRiskScore: Int,
    val cumulativeRiskScore: Int,
    val verdict: CallVerdict,
    val reasons: List<String>,
    val keywords: List<String>,
    val elapsedSec: Float
)

enum class StreamStatus { Connected, GeminiConnected, Reconnecting, Error, Disconnected }
