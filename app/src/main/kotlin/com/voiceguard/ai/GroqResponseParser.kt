package com.voiceguard.ai

import com.voiceguard.stt.AudioSource

/**
 * Defensive parser for the Groq chat-completions response. Hand-rolled (no JSON
 * library) so it stays unit-testable on the JVM. Returns null when the payload
 * carries no usable verdict.
 */
object GroqResponseParser {
    fun parseResponse(body: String, source: AudioSource = AudioSource.LIVE): RiskVerdict? {
        val content = extractAssistantContent(body) ?: body
        val clean = stripFences(content).trim()
        if (clean.isEmpty()) return null
        val risk = extractInt(clean, "risk")?.coerceIn(0, 100)
        val verdictRaw = extractString(clean, "verdict")?.uppercase() ?: ""
        val verdict = when (verdictRaw) {
            "SCAM" -> Verdict.SCAM
            "SUSPICIOUS" -> Verdict.SUSPICIOUS
            "SAFE" -> Verdict.SAFE
            else -> null
        }
        val reasons = extractStringArray(clean, "reasons")
        val tactics = extractStringArray(clean, "tactics")
        val advice = extractString(clean, "advice") ?: ""
        // Require at least a risk number or a known verdict.
        if (risk == null && verdict == null) return null
        val r = risk ?: when (verdict) {
            Verdict.SCAM -> 85
            Verdict.SUSPICIOUS -> 50
            Verdict.SAFE -> 5
            else -> 0
        }
        val v = verdict ?: when {
            r >= 70 -> Verdict.SCAM
            r >= 30 -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }
        return RiskVerdict(r, v, reasons.take(3), tactics, advice, source)
    }

    /** Extracts choices[0].message.content from the OpenAI-style envelope. */
    fun extractAssistantContent(body: String): String? {
        val key = "\"content\""
        var idx = body.indexOf(key)
        while (idx >= 0) {
            var i = idx + key.length
            while (i < body.length && (body[i] == ' ' || body[i] == ':')) i++
            if (i < body.length && body[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < body.length) {
                    val c = body[i]
                    if (c == '\\' && i + 1 < body.length) {
                        val n = body[i + 1]
                        when (n) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            '"', '\\', '/' -> sb.append(n)
                            'u' -> {
                                val hex = body.substring(i + 2, minOf(i + 6, body.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                            else -> sb.append(n)
                        }
                        i += 2
                    } else if (c == '"') {
                        return sb.toString()
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                return sb.toString()
            }
            idx = body.indexOf(key, idx + 1)
        }
        return null
    }

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            if (t.startsWith("json")) t = t.removePrefix("json")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) t = t.substring(start, end + 1)
        return t.trim()
    }

    private fun extractInt(json: String, key: String): Int? {
        val raw = rawValue(json, key) ?: return null
        return raw.trim().trimEnd(',', ' ', '}').toIntOrNull()
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
                val n = json[i + 1]
                sb.append(if (n == 'n') '\n' else n)
                i += 2
            } else {
                sb.append(json[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun extractStringArray(json: String, key: String): List<String> {
        val q = "\"$key\""
        val idx = json.indexOf(q)
        if (idx < 0) return emptyList()
        var i = idx + q.length
        while (i < json.length && (json[i] == ' ' || json[i] == ':')) i++
        if (i >= json.length || json[i] != '[') return emptyList()
        val end = json.indexOf(']', i)
        if (end < 0) return emptyList()
        val inner = json.substring(i + 1, end)
        val out = mutableListOf<String>()
        var j = 0
        while (j < inner.length) {
            val open = inner.indexOf('"', j)
            if (open < 0) break
            val sb = StringBuilder()
            var k = open + 1
            while (k < inner.length && inner[k] != '"') {
                if (inner[k] == '\\' && k + 1 < inner.length) {
                    sb.append(inner[k + 1])
                    k += 2
                } else {
                    sb.append(inner[k])
                    k++
                }
            }
            out.add(sb.toString())
            j = k + 1
        }
        return out
    }

    private fun rawValue(json: String, key: String): String? {
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
}

