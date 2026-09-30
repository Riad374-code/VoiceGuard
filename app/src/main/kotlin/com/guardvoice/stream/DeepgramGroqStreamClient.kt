package com.guardvoice.stream

import android.content.Context

/**
 * DeepgramGroqStreamClient
 * ------------------------
 * Preferred streaming client for 5-second windows with 1-second overlap
 * using Deepgram STT + Groq llama (or gpt-oss-20b) for instant scam scoring.
 *
 * This is a thin alias over GeminiLiveStreamClient which now implements the
 * 5s-overlap logic internally. Kept for explicit naming per new pipeline.
 * New code should use DeepgramGroqStreamClient; legacy GeminiLiveStreamClient
 * forwards to the same implementation for backward compatibility.
 */
object DeepgramGroqStreamClient {
    fun start(context: Context, sessionId: String) = GeminiLiveStreamClient.start(context, sessionId)
    fun stop() = GeminiLiveStreamClient.stop()
    fun sendPcmChunk(pcm: ByteArray) = GeminiLiveStreamClient.sendPcmChunk(pcm)
    fun feedFallbackTranscript(transcript: String) = GeminiLiveStreamClient.feedFallbackTranscript(transcript)
    fun isStreamingConnected(): Boolean = GeminiLiveStreamClient.isStreamingConnected()
}
