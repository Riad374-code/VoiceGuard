package com.guardvoice.call

import com.guardvoice.data.CallVerdict

data class AnalysisResult(
    val verdict: CallVerdict,
    val riskScore: Int,
    val reasons: List<String>
)

object ScamAnalyzer {
    private const val MIN_WORDS_FOR_SAFE_VERDICT = 6

    private data class ScamPattern(
        val keywords: List<String>,
        val reason: String,
        val weight: Int
    )

    private val patterns = listOf(
        ScamPattern(
            keywords = listOf("social security", "ssn", "social security number", "social security#"),
            reason = "Mentions government ID number",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf("irs", "internal revenue service", "tax refund", "unpaid taxes", "tax problem"),
            reason = "IRS impersonation scam",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf("medicare", "medicaid", "health insurance card", "new insurance card"),
            reason = "Government health program impersonation",
            weight = 25
        ),
        ScamPattern(
            keywords = listOf("gift card", "wire transfer", "western union", "money gram", "money transfer"),
            reason = "Requests unusual payment method",
            weight = 35
        ),
        ScamPattern(
            keywords = listOf("arrest", "warrant", "lawsuit", "legal action", "sue you", "federal offense"),
            reason = "Threatens legal consequences",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf("verify your account", "confirm your identity", "verify identity", "verify account"),
            reason = "Phishing attempt to steal credentials",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf("bank account", "credit card", "debit card", "routing number", "account number", "card number"),
            reason = "Requests financial account details",
            weight = 35
        ),
        ScamPattern(
            keywords = listOf("you've won", "prize", "lucky winner", "congratulations you", "sweepstakes", "lottery"),
            reason = "Fake lottery or prize scam",
            weight = 25
        ),
        ScamPattern(
            keywords = listOf("tech support", "computer virus", "your computer", "microsoft", "virus on your"),
            reason = "Fake tech support scam",
            weight = 25
        ),
        ScamPattern(
            keywords = listOf("grandparent", "grandson", "granddaughter", "relative in trouble", "bail money", "your grandson"),
            reason = "Emergency grandparent scam",
            weight = 35
        ),
        ScamPattern(
            keywords = listOf("urgent", "immediately", "right now", "don't hang up", "act now", "hurry"),
            reason = "Uses high-pressure tactics",
            weight = 15
        ),
        ScamPattern(
            keywords = listOf("police", "sheriff", "fbi", "government agency", "federal agent", "officer"),
            reason = "Falsely claims law enforcement authority",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf("bitcoin", "crypto", "cryptocurrency", "investment opportunity", "guaranteed return"),
            reason = "Crypto or investment scam",
            weight = 25
        ),
        ScamPattern(
            keywords = listOf("password", "pin", "otp", "one time password", "verification code", "auth code"),
            reason = "Attempts to steal authentication codes",
            weight = 40
        ),
        ScamPattern(
            keywords = listOf("refund", "overpayment", "processing fee", "pay a fee", "administration fee"),
            reason = "Advance fee or refund scam",
            weight = 20
        ),
        ScamPattern(
            keywords = listOf("exclusive offer", "limited time", "special deal", "free grant", "government grant"),
            reason = "Fake offer or grant scam",
            weight = 20
        ),
        ScamPattern(
            keywords = listOf("you owe", "pay immediately", "settle your debt", "collections", "overdue payment"),
            reason = "Fake debt collection threat",
            weight = 25
        ),
        ScamPattern(
            keywords = listOf(
                "bank transfer", "online banking", "bank login", "internet banking",
                "mobile banking", "card blocked", "suspicious activity", "bank representative",
                "security department", "update your bank", "change your password bank"
            ),
            reason = "Banking scam indicators",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf(
                "banka hesabınız", "kredi kartınız", "banka kartınız",
                "hesabınız bloke", "bloke oldu", "şüpheli işlem", "banka güvenlik",
                "banka temsilcisi", "şifreniz", "internet bankacılığı",
                "mobil bankacılık", "para transferi", "havale", "eft"
            ),
            reason = "Bankacılık dolandırıcılığı göstergeleri",
            weight = 30
        ),
        ScamPattern(
            keywords = listOf(
                "bank hesabınız", "kredit kartınız", "bank kartınız",
                "hesabınız blok", "şübhəli əməliyyat", "bank təhlükəsizlik",
                "bank nümayəndəsi", "şifrəniz", "internet bankçılıq",
                "mobil bankçılıq", "pul köçürməsi", "kart məlumatları"
            ),
            reason = "Bank fırıldaqçılığı göstəriciləri",
            weight = 30
        )
    )

    fun analyze(transcript: String): AnalysisResult {
        val lower = transcript.lowercase()
        var score = 0
        val matchedReasons = mutableListOf<String>()

        for (pattern in patterns) {
            for (keyword in pattern.keywords) {
                if (lower.contains(keyword)) {
                    score += pattern.weight
                    if (pattern.reason !in matchedReasons) {
                        matchedReasons.add(pattern.reason)
                    }
                    break
                }
            }
        }

        val clampedScore = score.coerceIn(0, 100)
        val verdict = when {
            clampedScore >= 60 -> CallVerdict.Scam
            clampedScore >= 25 -> CallVerdict.Suspicious
            clampedScore > 0 -> CallVerdict.Suspicious
            hasEnoughSpeechForSafeVerdict(transcript) -> CallVerdict.Safe
            else -> CallVerdict.Pending
        }

        return AnalysisResult(
            verdict = verdict,
            riskScore = clampedScore,
            reasons = matchedReasons
        )
    }

    private fun hasEnoughSpeechForSafeVerdict(transcript: String): Boolean =
        transcript.trim()
            .split(Regex("\\s+"))
            .count { word -> word.isNotBlank() } >= MIN_WORDS_FOR_SAFE_VERDICT
}
