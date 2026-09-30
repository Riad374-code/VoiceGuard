package com.voiceguard.demo

import com.voiceguard.ai.RiskVerdict
import com.voiceguard.ai.Verdict
import com.voiceguard.stt.AudioSource
import com.voiceguard.stt.TranscriptEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class DemoLine(val delayMs: Long, val text: String)

data class DemoScenario(
    val id: String,
    val title: String,
    val lines: List<DemoLine>,
    /** Used only when Settings → demoOffline is ON (no network). */
    val canned: RiskVerdict
)

/**
 * Mode D script runner. Emits scripted [TranscriptEvent]s (source=SIMULATION) at
 * realistic pacing into the normal aggregator → scorer path. Every result is
 * flagged SIMULATION in history/exports and the UI shows a persistent banner.
 */
class DemoScriptRunner {
    val scenarios: List<DemoScenario> = listOf(
        DemoScenario(
            id = "normal_az",
            title = "Normal call (AZ)",
            lines = listOf(
                DemoLine(800, "Salam, necəsən?"),
                DemoLine(2200, "Salam, yaxşıyam, sən necəsən?"),
                DemoLine(2000, "Sabah görüşərik? Saat üçdə kafedə."),
                DemoLine(2400, "Əla, üçdə orada olaram. Sağ ol, hələlik."),
                DemoLine(1800, "Hələlik, sabah görüşənədək.")
            ),
            canned = RiskVerdict(4, Verdict.SAFE,
                listOf("Everyday small talk, no requests"),
                emptyList(), "Nothing suspicious in this call.", AudioSource.SIMULATION)
        ),
        DemoScenario(
            id = "bank_tr",
            title = "Bank impersonation + OTP (TR)",
            lines = listOf(
                DemoLine(800, "Alo, iyi günler, ABC Bank müşteri hizmetlerinden arıyorum, ismim Emre."),
                DemoLine(2400, "Kartınızda bu sabah şüpheli bir işlem denemesi tespit ettik."),
                DemoLine(2600, "Doğrulama için kartınızın ön yüzündeki numarayı alabilir miyim?"),
                DemoLine(2600, "Ardından telefonunuza gelen onay kodunu da okuyun lütfen."),
                DemoLine(2400, "Acele edin, hesabınız her an dondurulabilir!"),
                DemoLine(2200, "Kodu söylemezseniz paranız gider, vaktimiz yok, hemen!")
            ),
            canned = RiskVerdict(93, Verdict.SCAM,
                listOf("Caller claims to be bank security", "Requests card number and OTP code", "Creates false urgency"),
                listOf("impersonation of bank", "OTP request", "urgency"),
                "Hang up and call your bank on its official number.", AudioSource.SIMULATION)
        ),
        DemoScenario(
            id = "tech_en",
            title = "Tech-support remote access (EN)",
            lines = listOf(
                DemoLine(800, "Hello sir, I am calling from Microsoft support, your computer is infected."),
                DemoLine(2600, "Please install AnyDesk so our technician can fix it remotely."),
                DemoLine(2600, "Also buy three gift cards to pay for the license, quick, before the virus spreads."),
                DemoLine(2400, "Do not tell anyone, keep this secret or your files will be deleted!")
            ),
            canned = RiskVerdict(88, Verdict.SCAM,
                listOf("Claims to be Microsoft support", "Requests remote access and gift cards", "Demands secrecy"),
                listOf("remote access", "gift cards", "secrecy pressure"),
                "Real support never asks for gift cards or secrecy.", AudioSource.SIMULATION)
        )
    )

    suspend fun run(scenario: DemoScenario, emit: suspend (TranscriptEvent) -> Unit) {
        for (line in scenario.lines) {
            currentCoroutineContext().ensureActive()
            delay(line.delayMs)
            // Interim first, then final — mimics live STT pacing.
            emit(TranscriptEvent(line.text.take(line.text.length / 2), false, 0.6f, System.currentTimeMillis(), AudioSource.SIMULATION))
            delay(350)
            currentCoroutineContext().ensureActive()
            emit(TranscriptEvent(line.text, true, 0.92f, System.currentTimeMillis(), AudioSource.SIMULATION))
        }
    }
}

