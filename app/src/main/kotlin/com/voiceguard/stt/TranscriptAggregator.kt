package com.voiceguard.stt

/** Emitted when the rolling transcript has enough fresh words to justify scoring. */
data class ScoreRequest(val windowText: String, val newWords: Int)

/**
 * Pure-Kotlin transcript bookkeeping (JVM-testable). Keeps finalized segments,
 * the current interim string, and a rolling ~60-word window. Triggers scoring
 * when a final segment lands on >= [minWords], every [debounceSec] with >=
 * [minWords] new words, or on utterance end.
 */
class TranscriptAggregator(
    var minWords: Int = 4,
    var debounceSec: Long = 8
) {
    private val finals = ArrayDeque<String>()
    private var interim = ""
    private var lastScoreAt = 0L
    private var wordsAtLastScore = 0
    private var totalWords = 0
    private val seenFinals = HashSet<String>()

    data class Snapshot(
        val fullText: String,
        val interim: String,
        val rollingWindow: String,
        val wordCount: Int
    )

    fun reset() {
        finals.clear()
        interim = ""
        lastScoreAt = 0L
        wordsAtLastScore = 0
        totalWords = 0
        seenFinals.clear()
    }

    /** Returns a [ScoreRequest] when scoring should run, else null. */
    fun onEvent(e: TranscriptEvent, nowMs: Long = System.currentTimeMillis()): ScoreRequest? {
        if (e.text.isBlank()) {
            // Utterance-end marker with no text.
            return maybePeriodic(nowMs, force = true)
        }
        if (!e.isFinal) {
            interim = e.text
            return maybePeriodic(nowMs, force = false)
        }
        interim = ""
        val text = e.text.trim()
        if (!seenFinals.add(text)) return maybePeriodic(nowMs, force = false) // dedupe repeated finals
        finals.addLast(text)
        totalWords += countWords(text)
        while (finals.size > 40) finals.removeFirst()
        val window = rollingWindow()
        return if (countWords(window) >= minWords) {
            val fresh = totalWords - wordsAtLastScore
            lastScoreAt = nowMs
            wordsAtLastScore = totalWords
            ScoreRequest(window, fresh)
        } else {
            maybePeriodic(nowMs, force = false)
        }
    }

    private fun maybePeriodic(nowMs: Long, force: Boolean): ScoreRequest? {
        val newWords = totalWords - wordsAtLastScore
        val due = lastScoreAt == 0L || nowMs - lastScoreAt >= debounceSec * 1000
        if (newWords >= minWords && (due || force)) {
            lastScoreAt = nowMs
            wordsAtLastScore = totalWords
            return ScoreRequest(rollingWindow(), newWords)
        }
        return null
    }

    fun snapshot(): Snapshot {
        val full = (finals.toList() + listOfNotNull(interim.takeIf { it.isNotBlank() })).joinToString(" ")
        return Snapshot(full, interim, rollingWindow(), totalWords + countWords(interim))
    }

    private fun rollingWindow(maxWords: Int = 60): String {
        val words = finals.flatMap { it.split("\\s+".toRegex()) }.filter { it.isNotEmpty() }
        return words.takeLast(maxWords).joinToString(" ")
    }

    companion object {
        fun countWords(s: String): Int =
            if (s.isBlank()) 0 else s.trim().split("\\s+".toRegex()).size
    }
}

