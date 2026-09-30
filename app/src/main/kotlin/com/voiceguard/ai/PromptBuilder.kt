package com.voiceguard.ai

/** Builds the Groq chat payload. Transcript may be any language; reasons must come back in [uiLang]. */
object PromptBuilder {
    fun system(uiLang: String): String = """
        You are a fraud/scam phone-call risk analyst. The transcript may be in any language
        (Azerbaijani, Turkish, Russian, English, Hindi, Urdu, Bengali, or others).
        Score how likely this call is a scam or fraud attempt.
        Reply with JSON ONLY, no other text, exactly this shape:
        {"risk": 0-100, "verdict": "SAFE|SUSPICIOUS|SCAM", "reasons": ["..."], "tactics": ["..."], "advice": "..."}
        Known scam tactics: urgency, OTP request, impersonation of bank/police, gift cards,
        remote access, prize/lottery, threats, secrecy pressure.
        Write "reasons" (max 3) and "advice" (one short sentence) in this language: $uiLang.
        Use verdict SCAM only when risk >= 70, SUSPICIOUS for 30-69, SAFE below 30.
    """.trimIndent()

    fun user(transcript: String, previousRisk: Int?): String = buildString {
        if (previousRisk != null && previousRisk >= 0) {
            append("Previous risk for this call: $previousRisk. ")
        }
        append("Transcript:\n")
        append(transcript)
    }
}

