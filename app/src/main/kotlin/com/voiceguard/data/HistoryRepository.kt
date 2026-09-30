package com.voiceguard.data

import android.content.Context
import timber.log.Timber
import com.voiceguard.ai.Verdict
import com.voiceguard.stt.AudioSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SessionRecord(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val mode: String,
    val source: String,
    val verdict: String,
    val risk: Int?,
    val transcript: String,
    val reasons: List<String>,
    val advice: String
)

/**
 * File-based session history (JSON lines in internal storage). Room was
 * deliberately not used: the schema is append-only and tiny, and this keeps
 * the build dependency-light. Documented in README.
 */
class HistoryRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "history.jsonl")

    @Synchronized
    fun save(record: SessionRecord) {
        try {
            file.appendText(json.encodeToString(record) + "\n")
        } catch (t: Throwable) {
            Timber.w(t, "History save failed")
        }
    }

    @Synchronized
    fun list(): List<SessionRecord> {
        val f = file
        if (!f.isFile) return emptyList()
        return try {
            f.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { json.decodeFromString<SessionRecord>(line) } catch (_: Exception) { null }
                }
                .sortedByDescending { it.id }
        } catch (t: Throwable) {
            Timber.w(t, "History read failed")
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        try { if (file.isFile) file.delete() } catch (_: Exception) {}
    }

    fun exportText(r: SessionRecord): String = buildString {
        appendLine("VoiceGuard session ${r.id}")
        appendLine("Mode: ${r.mode}  Source: ${r.source}")
        appendLine("Verdict: ${r.verdict}  Risk: ${r.risk?.let { "$it%" } ?: "not scored"}")
        if (r.reasons.isNotEmpty()) {
            appendLine("Reasons:")
            r.reasons.forEach { appendLine("- $it") }
        }
        if (r.advice.isNotBlank()) appendLine("Advice: ${r.advice}")
        appendLine()
        appendLine("Transcript:")
        appendLine(r.transcript.ifBlank { "(empty)" })
    }

    companion object {
        fun build(
            mode: String, source: AudioSource, verdict: Verdict, risk: Int?,
            transcript: String, reasons: List<String>, advice: String,
            startedAtMs: Long
        ) = SessionRecord(
            id = System.currentTimeMillis(),
            startedAtMs = startedAtMs,
            endedAtMs = System.currentTimeMillis(),
            mode = mode,
            source = source.name,
            verdict = verdict.name,
            risk = risk,
            transcript = transcript,
            reasons = reasons,
            advice = advice
        )
    }
}

