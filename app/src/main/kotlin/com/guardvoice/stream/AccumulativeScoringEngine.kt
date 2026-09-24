package com.guardvoice.stream

import com.guardvoice.call.AnalysisResult
import com.guardvoice.call.ScamAnalyzer
import com.guardvoice.data.CallVerdict

/**
 * Kotlin port of backend AccumulativeScoringEngine.
 * Used as client-side fallback when WebSocket is unavailable,
 * and to keep local UI scoring consistent with server.
 *
 * Behavior: appendTranscript() called per 100ms-1s chunk; every 30s a window is finalized.
 * Cumulative score is monotonic max (decays slowly on long safe streaks) — meters don't flicker.
 */
class AccumulativeScoringEngine(
    private val windowDurationSec: Int = 30,
    private val interimEmitEverySec: Int = 1
) {
    data class WindowSnapshot(
        val windowIndex: Int,
        val windowStartSec: Int,
        val windowEndSec: Int,
        val transcriptWindow: String,
        val riskScore: Int,
        val verdict: CallVerdict,
        val reasons: List<String>,
        val keywords: List<String>
    )

    private var startTimeMs: Long? = null
    private var fullTranscript: String = ""
    private val windowBuffers = mutableMapOf<Int, String>()
    private val windowHistory = mutableListOf<WindowSnapshot>()
    private var cumulativeScore: Int = 0
    private val cumulativeReasons = mutableSetOf<String>()
    private val cumulativeKeywords = mutableSetOf<String>()
    private var lastInterimSec: Int = -1

    fun start(nowMs: Long = System.currentTimeMillis()) {
        startTimeMs = nowMs
        fullTranscript = ""
        windowBuffers.clear()
        windowHistory.clear()
        cumulativeScore = 0
        cumulativeReasons.clear()
        cumulativeKeywords.clear()
        lastInterimSec = -1
    }

    fun elapsedSec(nowMs: Long = System.currentTimeMillis()): Float {
        val s = startTimeMs ?: return 0f
        return (nowMs - s) / 1000f
    }

    /** Returns window snapshot if a window completed on this append. */
    fun appendTranscript(delta: String, nowMs: Long = System.currentTimeMillis()): WindowSnapshot? {
        val trimmed = delta.trim()
        if (trimmed.isEmpty()) return tick(nowMs)
        if (startTimeMs == null) start(nowMs)

        fullTranscript = if (fullTranscript.isEmpty()) trimmed else "$fullTranscript $trimmed"
        val elapsed = elapsedSec(nowMs)
        val winIdx = (elapsed / windowDurationSec).toInt()
        windowBuffers[winIdx] = (windowBuffers[winIdx]?.let { "$it $trimmed" } ?: trimmed)

        // Finalize any newly completed windows
        val completedWindows = (elapsed / windowDurationSec).toInt()
        var lastCompleted: WindowSnapshot? = null
        for (idx in 0 until completedWindows) {
            if (windowHistory.none { it.windowIndex == idx }) {
                finalizeWindow(idx)?.let { lastCompleted = it }
            }
        }
        return lastCompleted
    }

    fun tick(nowMs: Long = System.currentTimeMillis()): WindowSnapshot? {
        if (startTimeMs == null) return null
        val elapsed = elapsedSec(nowMs)
        val completedWindows = (elapsed / windowDurationSec).toInt()
        var lastCompleted: WindowSnapshot? = null
        for (idx in 0 until completedWindows) {
            if (windowHistory.none { it.windowIndex == idx }) {
                finalizeWindow(idx)?.let { lastCompleted = it }
            }
        }
        return lastCompleted
    }

    private fun finalizeWindow(windowIndex: Int): WindowSnapshot? {
        val text = (windowBuffers[windowIndex] ?: "").trim()
        val local: AnalysisResult = if (text.isNotEmpty()) ScamAnalyzer.analyze(text)
        else AnalysisResult(CallVerdict.Pending, 0, emptyList())

        val score = local.riskScore
        if (score > cumulativeScore) {
            cumulativeScore = score
        } else {
            // Slow decay on safe streak (matches backend)
            val isSafeStreak = score == 0 && windowHistory.takeLast(1).all { it.riskScore == 0 }
            if (isSafeStreak && cumulativeScore > 0) {
                cumulativeScore = maxOf(0, cumulativeScore - 3)
            }
        }
        local.reasons.forEach { cumulativeReasons.add(it) }

        val snapshot = WindowSnapshot(
            windowIndex = windowIndex,
            windowStartSec = windowIndex * windowDurationSec,
            windowEndSec = (windowIndex + 1) * windowDurationSec,
            transcriptWindow = text,
            riskScore = score,
            verdict = local.verdict,
            reasons = local.reasons.toList(),
            keywords = emptyList() // keywords not tracked separately; reasons cover it
        )
        windowHistory.add(snapshot)
        return snapshot
    }

    fun getFullTranscript(): String = fullTranscript
    fun getCumulativeScore(): Int = cumulativeScore
    fun getCumulativeReasons(): List<String> = cumulativeReasons.toList()
    fun getWindowHistory(): List<WindowSnapshot> = windowHistory.toList()
    fun getCurrentWindowPartial(nowMs: Long = System.currentTimeMillis()): String {
        val elapsed = elapsedSec(nowMs)
        val winIdx = (elapsed / windowDurationSec).toInt()
        return (windowBuffers[winIdx] ?: "").trim()
    }
}
