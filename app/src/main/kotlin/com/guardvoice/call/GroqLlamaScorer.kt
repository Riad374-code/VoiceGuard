package com.guardvoice.call

import android.util.Log
import com.guardvoice.BuildConfig
import com.guardvoice.data.CallVerdict
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GroqLlamaScorer
 * ---------------
 * On-device scam scoring via the Groq OpenAI-compatible chat API
 * (mirrors backend GroqAnalyzer.ts).
 *
 * Model chain (verified live on Groq 2026-09-28; Llama 3.1/3.3 retired):
 * preferred model (or BuildConfig.GROQ_MODEL, default openai/gpt-oss-120b)
 *   -> openai/gpt-oss-20b -> qwen/qwen3.8-27b -> local ScamAnalyzer fallback.
 *
 * Blocking network call — must run on a background thread (CallAudioStream executor).
 */
object GroqLlamaScorer {
    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TAG = "GroqLlamaScorer"
    private const val MIN_WORDS_FOR_LLM = 4

    data class LlamaVerdict(
        val verdict: CallVerdict,
        val riskScore: Int,
        val reasons: List<String>,
        val keywords: List<String>
    )

    fun score(transcript: String, preferredModel: String? = null): LlamaVerdict {
        val trimmed = transcript.trim()
        val apiKey = try { BuildConfig.GROQ_API_KEY.trim() } catch (_: Exception) { "" }
        // Fast path (mirrors backend): very short utterances score locally, no tokens spent.
        if (apiKey.isBlank() || wordCount(trimmed) < MIN_WORDS_FOR_LLM) {
            return localFallback(trimmed)
        }
        val configured = try { BuildConfig.GROQ_MODEL.ifBlank { "openai/gpt-oss-120b" } } catch (_: Exception) { "openai/gpt-oss-120b" }
        val primary = preferredModel?.ifBlank { null } ?: configured
        // Fast 20b first for live windows, strong 120b for authoritative windows —
        // distinct() keeps whichever is primary, other becomes first fallback.
        // Capped at 2 attempts: reasoning models are slow, and every model past
        // the first burns another full timeout on the single scoring thread
        // (4 models x ~23s stalled the live meter minutes behind the call).
        val models = listOf(primary, "openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b").distinct().take(2)
        var lastError = ""
        for (model in models) {
            try {
                val result = callGroq(model, trimmed, apiKey) ?: continue
                return blendWithLocal(trimmed, result)
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                Log.w(TAG, "Groq model $model failed ($lastError); trying fallback.")
            }
        }
        Log.w(TAG, "All Groq models failed ($lastError); using local ScamAnalyzer.")
        return localFallback(trimmed)
    }

    private fun localFallback(transcript: String): LlamaVerdict {
        val a = ScamAnalyzer.analyze(transcript)
        return LlamaVerdict(a.verdict, a.riskScore, a.reasons, emptyList())
    }

    private fun callGroq(model: String, transcript: String, apiKey: String): LlamaVerdict? {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Transcript to score (5 sec window): \"\"\"$transcript\"\"\"\n\nReturn JSON only.")
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 400)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            // Browser-like UA: Groq sits behind Cloudflare bot rules that 403
            // non-browser clients (verified 2026-09-28, error 1010 vs Java UA).
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            connection.doOutput = true
            // Reasoning models (120b/qwen) think 20-60s before the first token —
            // a flat 15s read timeout guaranteed fallback on exactly the strong
            // models. Timeouts scale with the model.
            val (connectMs, readMs) = when {
                model.contains("120b") -> 10_000 to 60_000
                model.contains("qwen") -> 10_000 to 45_000
                else -> 8_000 to 20_000
            }
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs

            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code != 200) {
                val errBody = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("Groq $code: ${errBody.take(300)}")
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val message = JSONObject(response)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
            // Reasoning models (gpt-oss/qwen) can put the answer in `reasoning`
            // with `content` empty — treating that as failure wasted the whole
            // fallback chain on a successful call.
            val content = message?.optString("content", "").orEmpty()
                .ifBlank { message?.optString("reasoning", "").orEmpty() }
            if (content.isBlank()) throw IllegalStateException("Empty Groq response")
            return parseVerdict(content, transcript)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVerdict(content: String, transcript: String): LlamaVerdict? {
        var s = content.trim()
        if (s.startsWith("```")) {
            val first = s.indexOf('\n')
            val last = s.lastIndexOf("```")
            if (first != -1 && last != -1) s = s.substring(first + 1, last).trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        val obj = try { JSONObject(s.substring(start, end + 1)) } catch (_: Exception) { return null }

        var risk = obj.optDouble("risk", Double.NaN)
        if (risk.isNaN()) risk = obj.optDouble("riskScore", obj.optDouble("score", 0.0))
        risk = risk.coerceIn(0.0, 100.0)
        val riskScore = risk.toInt()

        val keywords = mutableListOf<String>()
        obj.optJSONArray("keywords")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { keywords.add(it) }
        }
        val reasons = mutableListOf<String>()
        obj.optJSONArray("reasons")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let { reasons.add(it) }
        }
        val verdict = when (obj.optString("verdict", "").lowercase()) {
            "scam" -> CallVerdict.Scam
            "suspicious" -> CallVerdict.Suspicious
            "safe" -> CallVerdict.Safe
            "pending" -> CallVerdict.Pending
            else -> when {
                riskScore >= 60 -> CallVerdict.Scam
                riskScore >= 25 -> CallVerdict.Suspicious
                riskScore > 0 -> CallVerdict.Suspicious
                wordCount(transcript) >= MIN_WORDS_FOR_LLM -> CallVerdict.Safe
                else -> CallVerdict.Pending
            }
        }
        return LlamaVerdict(verdict, riskScore, reasons, keywords)
    }

    /**
     * Safety blend (mirrors backend): if the local keyword analyzer sees an explicit
     * high-risk pattern the LLM missed, lift the risk instead of trusting the LLM.
     */
    private fun blendWithLocal(transcript: String, remote: LlamaVerdict): LlamaVerdict {
        val local = ScamAnalyzer.analyze(transcript)
        if (local.riskScore <= remote.riskScore + 15) return remote
        val risk = maxOf(remote.riskScore, local.riskScore)
        val verdict = when {
            risk >= 60 -> CallVerdict.Scam
            risk >= 25 -> CallVerdict.Suspicious
            else -> remote.verdict
        }
        val reasons = (remote.reasons + local.reasons).distinct()
        return LlamaVerdict(verdict, risk, reasons, remote.keywords)
    }

    private fun wordCount(text: String): Int =
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private const val SYSTEM_PROMPT = """You are VoiceGuard, real-time scam call detector. Input is a transcript chunk (English, Turkish, Azerbaijani, Russian — keep language intact). Score SCAM risk for THIS chunk: 0=safe, 1-24=low, 25-59=suspicious, 60-100=scam. If <6 words or greeting only: risk 0, empty reasons/keywords.
Detect: OTP/password requests, bank/police impersonation, urgency/pressure, gift card/wire/crypto, prize/lottery, arrest threats, SSN/bank details requests, crypto investment, tech support, family emergency.
Output STRICT JSON only, exact schema: {"risk": 0-100, "keywords": ["verbatim phrase from transcript"], "reasons": ["one-sentence English reason"], "verdict": "safe|suspicious|scam|pending"}
Example: "Hello, this is bank security, your card is blocked, give your OTP" -> {"risk": 85, "keywords": ["bank security", "card is blocked", "OTP"], "reasons": ["Impersonates bank security", "Urgency: claims card blocked", "Requests OTP"], "verdict": "scam"}"""
}
