package com.guardvoice.call

import com.guardvoice.data.CallVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamAnalyzerTest {
    @Test
    fun `marks normal conversation as safe`() {
        val result = ScamAnalyzer.analyze("Hi, I am calling to confirm our lunch at noon.")

        assertEquals(CallVerdict.Safe, result.verdict)
        assertEquals(0, result.riskScore)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `keeps tiny transcript fragments pending instead of safe`() {
        val result = ScamAnalyzer.analyze("Thank you.")

        assertEquals(CallVerdict.Pending, result.verdict)
        assertEquals(0, result.riskScore)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `marks high pressure credential request as scam`() {
        val result = ScamAnalyzer.analyze(
            "This is urgent. Verify your account and give me the one time password right now."
        )

        assertEquals(CallVerdict.Scam, result.verdict)
        assertTrue(result.riskScore >= 60)
        assertTrue(result.reasons.any { reason -> reason.contains("credentials", ignoreCase = true) })
    }

    @Test
    fun `detects english banking scam patterns`() {
        val result = ScamAnalyzer.analyze(
            "I am a bank representative calling about suspicious activity on your account. We need you to verify your internet banking login."
        )

        assertEquals(CallVerdict.Suspicious, result.verdict)
        assertTrue(result.riskScore >= 25)
        assertTrue(result.reasons.any { it.contains("Banking scam", ignoreCase = true) })
    }

    @Test
    fun `detects turkish banking scam patterns`() {
        val result = ScamAnalyzer.analyze(
            "Merhaba, ben banka temsilcisi. Hesabınız bloke oldu ve şüpheli işlem tespit edildi. Şifreniz ve internet bankacılığı bilgilerinizi doğrulamamız gerekiyor."
        )

        assertEquals(CallVerdict.Scam, result.verdict)
        assertTrue(result.riskScore >= 60)
        assertTrue(result.reasons.any { it.contains("Bankacılık", ignoreCase = true) })
    }

    @Test
    fun `detects azerbaijani banking scam patterns`() {
        val result = ScamAnalyzer.analyze(
            "Salam, mən bank nümayəndəsiyəm. Hesabınız blok olunub, şübhəli əməliyyat aşkar edilib. Kart məlumatları və şifrənizi təsdiqləməyiniz lazımdır."
        )

        assertEquals(CallVerdict.Suspicious, result.verdict)
        assertTrue(result.riskScore >= 25)
        assertTrue(result.reasons.any { it.contains("Bank fırıldaqçılığı", ignoreCase = true) })
    }
}
