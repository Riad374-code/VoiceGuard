package com.guardvoice.call

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.CallVerdict
import com.guardvoice.db.GuardVoiceRepository
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DemoCallEngine — imitation mode.
 * -------------------------------
 * Plays a scripted scam call (transcript + climbing risk + reasons) through
 * the EXACT same pipeline a real call uses: CallSessionRepository writes
 * (drives the in-app Compose screens + history) and ACTION_* broadcasts
 * (drive the floating overlay). No microphone, no network, no tokens.
 *
 * The UI is always labeled "Demo simulation" — this is a presentation /
 * testing mode, never presented as a real detection.
 */
object DemoCallEngine {
    private const val TAG = "DemoCallEngine"
    const val DEMO_NUMBER = "+994 51 425 99 25"

    /**
     * One spoken turn. windowScore = this chunk's own risk (like one Groq
     * verdict on a 5s window) — safe small-talk scores LOW, scam markers
     * score HIGH. What the user sees is the running average, so the meter
     * honestly dips on innocent turns and climbs on scammy ones.
     */
    private data class Step(
        val delaySec: Long,
        val line: String,
        val windowScore: Int,
        val reasons: List<String>,
        val keywords: List<String>
    )

    // A natural two-sided bank-OTP scam call, Turkish (~80s, 18 turns).
    // Small talk, politeness, a skeptical victim who pushes back twice —
    // like a real transcript, not a speech.
    // Reasons stay English to match the rest of the UI.
    private val SCRIPT = listOf(
        Step(2, "Arayan: Alo, iyi günler, nasılsınız?", 5, emptyList(), emptyList()),
        Step(6, "Siz: İyiyim, buyrun? Kiminle görüşüyorum?", 3, emptyList(), emptyList()),
        Step(10, "Arayan: ABC Bank müşteri hizmetlerinden arıyorum, ismim Emre.", 15,
            listOf("Caller claims to be bank security"), listOf("banka")),
        Step(14, "Siz: Evet Emre Bey, dinliyorum.", 4, emptyList(), emptyList()),
        Step(18, "Arayan: Öncelikle vaktinizi aldığım için kusura bakmayın.", 6, emptyList(), emptyList()),
        Step(22, "Siz: Estağfurullah, konu nedir?", 4, emptyList(), emptyList()),
        Step(27, "Arayan: Kartınızda bu sabah şüpheli bir işlem denemesi tespit ettik.", 35,
            listOf("Reports suspicious transaction"), listOf("şüpheli işlem")),
        Step(31, "Siz: Hangi kart? Ben hiç bildirim almadım...", 10, emptyList(), emptyList()),
        Step(35, "Arayan: Kredi kartınız, internet üzerinden denenmiş. Merak etmeyin, koruma altında.", 30,
            emptyList(), listOf("kredi kartı")),
        Step(39, "Siz: Tamam... ne yapmam gerekiyor?", 12, emptyList(), emptyList()),
        Step(44, "Arayan: Doğrulama için kartınızın ön yüzündeki numarayı alabilir miyim?", 55,
            listOf("Requests card number"), listOf("kart numarası")),
        Step(48, "Siz: Kart numaramı telefonda mı vereceğim? Emin misiniz?", 22, emptyList(), emptyList()),
        Step(52, "Arayan: Evet efendim, standart güvenlik prosedürü. Ardından size gelen kodu da okuyun.", 75,
            listOf("Requests OTP verification code"), listOf("kodu", "OTP")),
        Step(56, "Siz: Bir dakika... bankalar genelde böyle şey istemez.", 25, emptyList(), emptyList()),
        Step(61, "Arayan: Haklısınız ama işlem devam ediyor, vaktimiz çok az!", 70,
            listOf("Creates false urgency"), listOf("vaktimiz az")),
        Step(65, "Siz: Peki peki, sakin olun, kartı getireyim...", 30, emptyList(), emptyList()),
        Step(70, "Arayan: Çabuk olun lütfen, hesabınız her an dondurulabilir!", 90,
            listOf("Threatens account freeze"), listOf("dondurulabilir")),
        Step(74, "Arayan: Kodu söylemezseniz paranız gider, vaktimiz yok, hemen!", 95,
            listOf("Pressures victim to act immediately"), listOf("hemen"))
    )
    // Exponential moving average: recent windows weigh more, like the real
    // cumulative engine. α=0.35 → dips survive, scam spikes still convict.
    private const val EMA_ALPHA = 0.35
    private const val SCAM_THRESHOLD = 60
    private const val SUSPICIOUS_THRESHOLD = 20

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private var appContext: Context? = null
    private var sessionId: String = ""
    private var startRealtimeMs: Long = 0L
    private var scheduled: ScheduledFuture<*>? = null
    // Word-streaming state: words reveal progressively inside each step's time
    // window, score interpolates toward the step target — like live dictation,
    // never chunk dumps. All derived from wall-clock, so a late tick resumes
    // mid-word instead of bursting.
    private var lastEmittedText = ""
    private var lastEmittedScore = -1
    private val completedSteps = mutableSetOf<Int>()
    private const val TICK_MS = 500L

    fun isRunning(): Boolean = running.get()

    fun activeSessionId(): String = sessionId

    /** Last startup failure, empty when healthy — surfaced via Toast. */
    var lastError: String = ""
        private set

    private fun toast(context: Context, msg: String) {
        try {
            val app = context.applicationContext
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Starts the imitation: creates a demo session, optionally shows the
     * floating popup, and plays the script. Safe to call twice — the
     * previous run is stopped first.
     */
    fun start(context: Context, showOverlay: Boolean = true) {
        stop()
        lastError = ""
        val app = context.applicationContext
        appContext = app
        val sid = try {
            CallSessionRepository.recordDetected(app, DEMO_NUMBER)
        } catch (e: Exception) {
            Log.e(TAG, "Demo session creation failed", e)
            lastError = "session: ${e.message}"
            toast(context, "Demo failed (session): ${e.message}")
            return
        }
        sessionId = sid
        try {
            CallSessionRepository.markListening(app, sid)
        } catch (e: Exception) {
            Log.e(TAG, "Demo markListening failed", e)
            lastError = "listening: ${e.message}"
        }
        startRealtimeMs = SystemClock.elapsedRealtime()
        running.set(true)
        if (showOverlay) {
            try {
                CallOverlayService.showDemo(app, DEMO_NUMBER, sid)
            } catch (e: Exception) {
                Log.w(TAG, "Demo overlay could not be shown; in-app demo continues.", e)
                toast(context, "Popup blocked — demo continues in the app.")
            }
        }
        toast(context, "Demo simulation playing…")
        lastEmittedText = ""
        lastEmittedScore = -1
        completedSteps.clear()
        var task: ScheduledFuture<*>? = null
        task = executor.scheduleAtFixedRate({
            // One bad tick must never kill the schedule: the friend has no
            // logcat, a dead schedule looks like "nothing happens".
            try {
                tick(app, sid)
            } catch (e: Exception) {
                Log.e(TAG, "Demo tick failed", e)
            } catch (e: Error) {
                Log.e(TAG, "Demo tick failed hard", e)
            }
            if (!running.get()) {
                try {
                    task?.cancel(false)
                } catch (_: Exception) {
                }
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        scheduled = task
    }

    /**
     * Word-by-word playback with honest averaged scoring. Each step owns the
     * window [its delay, next step's delay): words reveal linearly, the shown
     * score glides from the previous average toward this window's average.
     * Safe turns visibly PULL the meter down — exactly like the real engine.
     */
    private fun tick(app: Context, sid: String) {
        if (!running.get()) return
        val elapsed = (SystemClock.elapsedRealtime() - startRealtimeMs) / 1000.0
        val visible = StringBuilder()
        var ema = 0.0
        var shownScore = 0
        val activeReasons = mutableListOf<String>()
        val activeKeywords = mutableListOf<String>()
        var wordCount = 0
        var allDone = SCRIPT.isNotEmpty()
        for (i in SCRIPT.indices) {
            val step = SCRIPT[i]
            val start = step.delaySec.toDouble()
            val end = if (i + 1 < SCRIPT.size) SCRIPT[i + 1].delaySec.toDouble() else step.delaySec + 6.0
            if (elapsed < start) {
                allDone = false
                break
            }
            val words = step.line.split(" ").filter { it.isNotBlank() }
            val progress = ((elapsed - start) / (end - start)).coerceIn(0.0, 1.0)
            // First word lands instantly so every turn visibly "starts talking".
            val shown = (progress * words.size).toInt().coerceIn(1, words.size)
            if (visible.isNotEmpty()) visible.append("\n")
            visible.append(words.take(shown).joinToString(" "))
            wordCount += shown
            // This window's average, glided by how much of it has been spoken.
            val windowEma = ema + EMA_ALPHA * (step.windowScore - ema)
            shownScore = (ema + progress * (windowEma - ema)).toInt()
            for (r in step.reasons) if (!activeReasons.contains(r)) activeReasons.add(r)
            for (k in step.keywords) if (!activeKeywords.contains(k)) activeKeywords.add(k)
            if (progress >= 1.0) {
                ema = windowEma
                if (completedSteps.add(i)) {
                    // Turn fully spoken — one granular history row, like a real 5s window.
                    try {
                        GuardVoiceRepository.getInstance(app).insertDetection(
                            sessionId = sid,
                            transcript = step.line,
                            verdict = verdictFor(ema.toInt(), wordCount),
                            riskScore = ema.toInt(),
                            reasons = step.reasons
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Demo persist failed", e)
                    }
                }
            } else {
                allDone = false
            }
        }
        val text = visible.toString()
        val verdict = verdictFor(shownScore, wordCount)
        if (text != lastEmittedText || shownScore != lastEmittedScore) {
            val delta = if (lastEmittedText.isNotEmpty() && text.startsWith(lastEmittedText)) {
                text.substring(lastEmittedText.length).trim()
            } else {
                text
            }
            lastEmittedText = text
            lastEmittedScore = shownScore
            emitDemoFrame(app, sid, text, delta, shownScore, verdict, activeReasons, activeKeywords)
        }
        if (allDone) finish(app, sid, text)
    }

    /** Same bands as the real cumulative engine: average convicts, not peaks. */
    private fun verdictFor(avgScore: Int, words: Int): CallVerdict = when {
        avgScore >= SCAM_THRESHOLD -> CallVerdict.Scam
        avgScore >= SUSPICIOUS_THRESHOLD -> CallVerdict.Suspicious
        words >= 4 -> CallVerdict.Safe
        else -> CallVerdict.Pending
    }

    fun stop() {
        running.set(false)
        try {
            scheduled?.cancel(false)
        } catch (_: Exception) {
        }
        scheduled = null
        val ctx = appContext
        val sid = sessionId
        // Always cleared: a finished run must never leak its id into the next one.
        sessionId = ""
        completedSteps.clear()
        if (ctx != null && sid.isNotBlank()) {
            try {
                CallSessionRepository.markCompleted(ctx, sid)
            } catch (_: Exception) {
            }
        }
    }

    /** One video frame of the demo: repo + broadcasts, no heavy I/O. */
    private fun emitDemoFrame(
        context: Context,
        sid: String,
        fullTranscript: String,
        delta: String,
        riskScore: Int,
        verdict: CallVerdict,
        reasons: List<String>,
        keywords: List<String>
    ) {
        val elapsed = ((SystemClock.elapsedRealtime() - startRealtimeMs) / 1000).toFloat()
        val summary = "[Demo] " + if (reasons.isNotEmpty()) {
            reasons.joinToString(". ")
        } else {
            "Demo simulation playing…"
        }
        try {
            CallSessionRepository.saveAnalysis(
                context, sid, verdict, riskScore,
                fullTranscript, summary, reasons
            )
        } catch (e: Exception) {
            Log.w(TAG, "Demo saveAnalysis failed", e)
        }
        try {
            context.sendBroadcast(
                Intent(AudioCaptureService.ACTION_TRANSCRIPT_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, fullTranscript)
                    .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT_DELTA, delta)
                    .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sid)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Demo transcript broadcast failed", e)
        }
        try {
            context.sendBroadcast(
                Intent(AudioCaptureService.ACTION_VERDICT_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(AudioCaptureService.EXTRA_RISK_LEVEL, verdict.name)
                    .putExtra(AudioCaptureService.EXTRA_RISK_SCORE, riskScore)
                    .putExtra(AudioCaptureService.EXTRA_TRANSCRIPT, fullTranscript)
                    .putExtra(AudioCaptureService.EXTRA_REASONS, reasons.toTypedArray())
                    .putExtra(AudioCaptureService.EXTRA_KEYWORDS, keywords.toTypedArray())
                    .putExtra(AudioCaptureService.EXTRA_ELAPSED_SEC, elapsed)
                    .putExtra(AudioCaptureService.EXTRA_SESSION_ID, sid)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Demo verdict broadcast failed", e)
        }
    }

    private fun finish(context: Context, sid: String, fullTranscript: String) {
        // Script complete: stop the schedule (no per-second markCompleted
        // spam), hold the final Scam verdict on screen. Overlay stays until
        // the user stops it; tapping Simulate again replays from scratch.
        running.set(false)
        try {
            scheduled?.cancel(false)
        } catch (_: Exception) {
        }
        scheduled = null
        try {
            CallSessionRepository.markCompleted(context, sid)
        } catch (_: Exception) {
        }
        Log.i(TAG, "Demo script complete ($sid): ${fullTranscript.length} chars played.")
    }
}
