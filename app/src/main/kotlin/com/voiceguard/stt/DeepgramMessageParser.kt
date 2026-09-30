package com.voiceguard.stt

data class ParsedTranscript(
    val text: String,
    val isFinal: Boolean,
    val speechFinal: Boolean,
    val utteranceEnd: Boolean,
    val confidence: Float,
    val requestId: String
)

/**
 * Minimal defensive parser for Deepgram `Results` messages. Hand-rolled string
 * scanning (no JSON library) so it stays unit-testable on the JVM.
 */
object DeepgramMessageParser {
    fun parse(json: String): ParsedTranscript? {
        if (!json.contains("\"type\"") && !json.contains("channel")) return null
        // Only Results messages carry transcripts; ignore Metadata/UtteranceEnd wrappers
        // for text purposes, but surface utterance_end when present.
        val utteranceEnd = json.contains("\"type\":\"UtteranceEnd\"") || json.contains("\"type\": \"UtteranceEnd\"")
        val type = extractString(json, "type")
        if (type != null && type != "Results" && !utteranceEnd) return null

        val transcript = extractTranscript(json) ?: ""
        val isFinal = extractBool(json, "is_final") ?: false
        val speechFinal = extractBool(json, "speech_final") ?: false
        val confidence = extractDouble(json, "confidence")?.toFloat() ?: 0f
        val requestId = extractNestedRequestId(json) ?: ""
        if (transcript.isEmpty() && !utteranceEnd) {
            // Empty Results (silence frames) — caller counts these.
            return ParsedTranscript("", isFinal, speechFinal, false, confidence, requestId)
        }
        return ParsedTranscript(transcript.trim(), isFinal, speechFinal, utteranceEnd, confidence, requestId)
    }

    private fun extractTranscript(json: String): String? {
        // First "transcript":"..." occurrence inside alternatives.
        val key = "\"transcript\""
        var idx = json.indexOf(key)
        while (idx >= 0) {
            var i = idx + key.length
            while (i < json.length && (json[i] == ' ' || json[i] == ':')) i++
            if (i < json.length && json[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < json.length) {
                    val c = json[i]
                    when {
                        c == '\\' && i + 1 < json.length -> {
                            val n = json[i + 1]
                            sb.append(
                                when (n) {
                                    'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                                    '"', '\\', '/' -> n
                                    'u' -> {
                                        val hex = json.substring(i + 2, minOf(i + 6, json.length))
                                        i += 4
                                        hex.toIntOrNull(16)?.toChar() ?: '?'
                                    }
                                    else -> n
                                }
                            )
                            i += 2
                        }
                        c == '"' -> return sb.toString()
                        else -> { sb.append(c); i++ }
                    }
                }
                return sb.toString()
            }
            idx = json.indexOf(key, idx + 1)
        }
        return null
    }

    private fun extractBool(json: String, key: String): Boolean? {
        val v = extractRawValue(json, key) ?: return null
        return when (v) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun extractDouble(json: String, key: String): Double? {
        val v = extractRawValue(json, key) ?: return null
        return v.trimEnd(',', ' ', '}', ']').toDoubleOrNull()
    }

    private fun extractString(json: String, key: String): String? {
        val q = "\"$key\""
        val idx = json.indexOf(q)
        if (idx < 0) return null
        var i = idx + q.length
        while (i < json.length && (json[i] == ' ' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length && json[i] != '"') {
            if (json[i] == '\\' && i + 1 < json.length) {
                sb.append(json[i + 1])
                i += 2
            } else {
                sb.append(json[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractRawValue(json: String, key: String): String? {
        val q = "\"$key\""
        val idx = json.indexOf(q)
        if (idx < 0) return null
        var i = idx + q.length
        while (i < json.length && (json[i] == ' ' || json[i] == ':')) i++
        if (i >= json.length) return null
        var j = i
        while (j < json.length && json[j] != ',' && json[j] != '}' && json[j] != '\n') j++
        return json.substring(i, j)
    }

    private fun extractNestedRequestId(json: String): String? {
        val meta = json.indexOf("\"metadata\"")
        if (meta < 0) return null
        val sub = json.substring(meta, minOf(meta + 500, json.length))
        return extractString(sub, "request_id")
    }
}

