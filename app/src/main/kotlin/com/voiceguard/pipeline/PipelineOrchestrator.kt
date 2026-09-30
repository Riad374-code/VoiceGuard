package com.voiceguard.pipeline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.PowerManager
import timber.log.Timber
import com.voiceguard.ai.GroqRiskScorer
import com.voiceguard.ai.RiskVerdict
import com.voiceguard.ai.Verdict
import com.voiceguard.audio.AudioCaptureEngine
import com.voiceguard.audio.AudioHealthAnalyzer
import com.voiceguard.audio.FileAudioDecoder
import com.voiceguard.audio.HealthClass
import com.voiceguard.audio.PcmChunk
import com.voiceguard.audio.VadGate
import com.voiceguard.data.AppSettings
import com.voiceguard.data.HistoryRepository
import com.voiceguard.data.SettingsRepository
import com.voiceguard.demo.DemoScriptRunner
import com.voiceguard.stt.AudioSource
import com.voiceguard.stt.DeepgramConnState
import com.voiceguard.stt.DeepgramStreamingClient
import com.voiceguard.stt.ScoreRequest
import com.voiceguard.stt.TranscriptAggregator
import com.voiceguard.stt.TranscriptEvent
import com.voiceguard.telephony.CallStateMonitor
import com.voiceguard.util.LogBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Wires Capture → Analyzer → VadGate → Deepgram → Aggregator → Groq → UI and
 * exposes a single [UiState]. The risk is a nullable Int: null means "not
 * scored yet" and the UI shows "—" instead of a misleading 0%.
 */
class PipelineOrchestrator(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val capture: AudioCaptureEngine,
    private val deepgram: DeepgramStreamingClient,
    private val scorer: GroqRiskScorer,
    private val callMonitor: CallStateMonitor,
    private val demo: DemoScriptRunner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _highRisk = MutableSharedFlow<RiskVerdict>(extraBufferCapacity = 4)
    val highRisk: SharedFlow<RiskVerdict> = _highRisk.asSharedFlow()

    private var session: Job? = null
    private var scorerJob: Job? = null
    private var pendingWindow: String? = null
    private var pendingSource: AudioSource = AudioSource.LIVE
    private var displayedRisk: Int? = null
    private var alertedHigh = false
    private var startedAtMs = 0L
    private var silenceSecs = 0.0
    private var lastAudioSentMs = 0L
    private var lastKeepAliveMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val aggregator = TranscriptAggregator()
    private val vad = VadGate()
    private val levelHist = ArrayDeque<Float>()

    val running: Boolean get() = session != null

    // ------------------------------------------------------------------ entry

    fun startListener() {
        if (running) return
        session = scope.launch {
            startedAtMs = System.currentTimeMillis()
            resetSession(ListenMode.LISTENER, AudioSource.LIVE, isSimulation = false)
            if (callMonitor.isInCall.value) {
                setStatus(Stage.IDLE, appContext.getString(com.voiceguard.R.string.phone_in_call_msg))
                endSession(save = false)
                return@launch
            }
            val s = settings.current()
            val key = settings.effectiveDeepgramKey()
            if (key.isBlank()) {
                setStatus(Stage.IDLE, "Deepgram API key is empty. Add it in Settings (or local.properties).")
                endSession(save = false)
                return@launch
            }
            aggregator.reset()
            aggregator.minWords = s.minWords
            aggregator.debounceSec = s.debounceSec.toLong()
            vad.reset()
            vad.setSensitivity(s.sensitivity)
            setStage(Stage.STARTING, "Starting microphone…")
            deepgram.connect(
                DeepgramStreamingClient.Config(
                    key, settings.effectiveDeepgramModel(s.deepgramModel),
                    s.deepgramLang, s.autoDetect, s.diarize, AudioSource.LIVE
                )
            )
            val src = withContext(Dispatchers.IO) { capture.start() }
            if (src == null) {
                setStatus(Stage.IDLE, capture.error.value ?: "Microphone could not start.")
                deepgram.close()
                endSession(save = false)
                return@launch
            }
            _ui.update { it.copy(micSource = src) }
            setStage(Stage.WAITING_FOR_SPEECH, "Waiting for speech – audio level OK")
            lastAudioSentMs = System.currentTimeMillis()
            lastKeepAliveMs = lastAudioSentMs
            launch { collectChunks(live = true) }
            launch { collectDeepgram() }
            launch { collectCaptureErrors() }
            launch { diagTicker() }
        }
    }

    fun startFile(uri: Uri, name: String) {
        if (running) return
        session = scope.launch {
            startedAtMs = System.currentTimeMillis()
            resetSession(ListenMode.FILE, AudioSource.FILE, isSimulation = false)
            _ui.update { it.copy(fileName = name, fileProgress = 0f) }
            val s = settings.current()
            val key = settings.effectiveDeepgramKey()
            if (key.isBlank()) {
                setStatus(Stage.IDLE, "Deepgram API key is empty. Add it in Settings (or local.properties).")
                endSession(save = false)
                return@launch
            }
            setStage(Stage.STARTING, "Decoding $name…")
            val decoded = try {
                withContext(Dispatchers.IO) { FileAudioDecoder.decode(appContext, uri) }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Decode failed")
                setStatus(Stage.IDLE, "Could not decode this file: ${t.message ?: "unsupported format"}")
                endSession(save = false)
                return@launch
            }
            if (decoded.samples16kMono.isEmpty()) {
                setStatus(Stage.IDLE, "Decoded file contains no audio.")
                endSession(save = false)
                return@launch
            }
            aggregator.reset()
            aggregator.minWords = s.minWords
            aggregator.debounceSec = s.debounceSec.toLong()
            vad.reset()
            vad.setSensitivity(s.sensitivity)
            acquireWake()
            deepgram.connect(
                DeepgramStreamingClient.Config(
                    key, settings.effectiveDeepgramModel(s.deepgramModel),
                    s.deepgramLang, s.autoDetect, s.diarize, AudioSource.FILE
                )
            )
            // Wait briefly for the socket before streaming.
            withContext(Dispatchers.IO) {
                val until = System.currentTimeMillis() + 8000
                while (deepgram.state.value != DeepgramConnState.OPEN &&
                    System.currentTimeMillis() < until
                ) {
                    Thread.sleep(150)
                    if (deepgram.state.value == DeepgramConnState.AUTH_ERROR) break
                }
            }
            if (deepgram.state.value == DeepgramConnState.AUTH_ERROR) {
                setStatus(Stage.IDLE, "Deepgram rejected the API key. Check the key in Settings.")
                endSession(save = false)
                return@launch
            }
            launch { collectDeepgram() }
            launch { diagTicker() }
            val chunks = FileAudioDecoder.toChunks(decoded.samples16kMono)
            setStage(Stage.TRANSCRIBING, "Streaming $name…")
            val realtimeDelay = !s.fileFastMode
            for ((i, c) in chunks.withIndex()) {
                if (!running) break
                handleChunk(c, live = false, bypassVad = !s.vadForFiles)
                _ui.update { it.copy(fileProgress = (i + 1).toFloat() / chunks.size) }
                if (realtimeDelay) delay(100) else delay(5)
            }
            // Flush: close stream so Deepgram finalizes, then stop.
            deepgram.close()
            delay(1200)
            finishFileOrDemo("File analyzed: $name")
        }
    }

    fun startDemo(scenarioId: String) {
        if (running) return
        val scenario = demo.scenarios.firstOrNull { it.id == scenarioId } ?: return
        session = scope.launch {
            startedAtMs = System.currentTimeMillis()
            resetSession(ListenMode.DEMO, AudioSource.SIMULATION, isSimulation = true)
            aggregator.reset()
            val s = settings.current()
            aggregator.minWords = minOf(s.minWords, 3)
            aggregator.debounceSec = 4
            acquireWake()
            setStage(Stage.TRANSCRIBING, "SIMULATION playing: ${scenario.title}")
            launch { diagTicker() }
            if (s.demoOffline) {
                demo.run(scenario) { e -> handleTranscript(e) }
                applyVerdict(scenario.canned, smooth = false)
            } else {
                demo.run(scenario) { e -> handleTranscript(e) }
            }
            delay(800)
            finishFileOrDemo("Simulation finished: ${scenario.title}")
        }
    }

    fun stop() {
        val job = session ?: return
        scope.launch {
            endSession(save = true)
            job.cancelAndJoin()
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun collectChunks(live: Boolean) {
        capture.chunks.collect { c ->
            if (!running) return@collect
            handleChunk(c, live = live, bypassVad = false)
            // KeepAlive while VAD is idle so interim context stays warm without billing audio.
            val now = System.currentTimeMillis()
            if (live && now - lastAudioSentMs > 1000 && now - lastKeepAliveMs > 5000) {
                lastKeepAliveMs = now
                deepgram.sendKeepAlive()
            }
        }
    }

    private suspend fun collectDeepgram() {
        deepgram.events.collect { e ->
            if (!running) return@collect
            handleTranscript(e)
        }
    }

    private suspend fun collectCaptureErrors() {
        capture.error.collect { err ->
            if (err != null && running) setStatus(_ui.value.stage, "Microphone error: $err")
        }
    }

    private suspend fun diagTicker() {
        while (running) {
            delay(1000)
            val dc = deepgram.counters()
            val gc = scorer.counters()
            _ui.update {
                it.copy(
                    deepgramState = deepgram.state.value,
                    inCall = callMonitor.isInCall.value,
                    vadFraction = vad.activeFraction(),
                    deepgram = DeepgramDiag(dc.requestId, dc.secondsSent, dc.messagesReceived, dc.wordsReceived, dc.emptyResults, dc.lastError),
                    groq = GroqDiag(gc.requestsSent, gc.lastLatencyMs, gc.tokensIn, gc.tokensOut, gc.lastError),
                    device = DeviceDiag(inCall = callMonitor.isInCall.value)
                )
            }
            if (callMonitor.isInCall.value && _ui.value.mode == ListenMode.LISTENER) {
                setStatus(_ui.value.stage, appContext.getString(com.voiceguard.R.string.phone_in_call_msg))
            }
        }
    }

    private fun handleChunk(chunk: PcmChunk, live: Boolean, bypassVad: Boolean) {
        val samples = AudioHealthAnalyzer.bytesToSamples(chunk.bytes)
        val health = AudioHealthAnalyzer.analyze(samples, capture.silencedByOs.value && live)
        val levelDb = AudioHealthAnalyzer.frameRmsDb(chunk.bytes)
        levelHist.addLast(AudioHealthAnalyzer.dbToLevel(levelDb))
        while (levelHist.size > 30) levelHist.removeFirst()

        if (health.classification == HealthClass.DIGITAL_SILENCE) {
            silenceSecs += chunk.bytes.size / 32000.0
        } else {
            silenceSecs = 0.0
        }
        val blocked = live && silenceSecs >= 3.0

        var vadActive = _ui.value.vadActive
        if (bypassVad) {
            deepgram.sendAudio(chunk.bytes)
            lastAudioSentMs = System.currentTimeMillis()
        } else if (health.classification != HealthClass.DIGITAL_SILENCE) {
            val d = vad.process(samples)
            vadActive = d.speechActive
            if (d.forward) {
                deepgram.sendAudio(chunk.bytes)
                lastAudioSentMs = System.currentTimeMillis()
            }
        }
        val snap = aggregator.snapshot()
        val stage = when {
            blocked -> _ui.value.stage
            snap.wordCount > 0 -> Stage.TRANSCRIBING
            vadActive -> Stage.SPEECH_DETECTED
            else -> Stage.WAITING_FOR_SPEECH
        }
        val status = when {
            blocked -> appContext.getString(com.voiceguard.R.string.no_audio_blocked)
            snap.wordCount > 0 -> "Transcribing – ${snap.wordCount} words so far"
            vadActive -> "Speech detected – transcribing…"
            else -> "Waiting for speech – audio level OK"
        }
        _ui.update {
            it.copy(
                health = health, levelDb = levelDb, levels = levelHist.toList(),
                vadActive = vadActive, silenceSeconds = silenceSecs,
                blockedByOs = blocked, stage = stage, statusText = status
            )
        }
    }

    private fun handleTranscript(e: TranscriptEvent) {
        val req: ScoreRequest? = aggregator.onEvent(e)
        val snap = aggregator.snapshot()
        _ui.update {
            it.copy(
                transcriptFull = snap.fullText, interim = snap.interim,
                stage = if (snap.wordCount > 0) Stage.TRANSCRIBING else it.stage,
                statusText = if (snap.wordCount > 0) "Transcribing – ${snap.wordCount} words so far" else it.statusText
            )
        }
        updateNoWordsHint()
        if (req != null) requestScore(req.windowText, e.source)
    }

    private fun updateNoWordsHint() {
        val dc = deepgram.counters()
        val hint = if (dc.secondsSent > 20 && dc.wordsReceived == 0L && vad.activeFraction() > 0.02) {
            "No words yet after ${dc.secondsSent.toInt()}s of voiced audio. Likely causes: wrong STT language, audio too quiet, or noise only. " +
                appContext.getString(com.voiceguard.R.string.no_words_hint)
        } else if (dc.messagesReceived > 5 && dc.wordsReceived == 0L) {
            appContext.getString(com.voiceguard.R.string.no_words_hint)
        } else null
        _ui.update {
            it.copy(
                noWordsWarning = hint,
                deepgramHint = if (dc.wordsReceived == 0L && dc.messagesReceived > 0) it.deepgramHint else null
            )
        }
    }

    private fun requestScore(window: String, source: AudioSource) {
        pendingWindow = window
        pendingSource = source
        if (scorerJob?.isActive == true) return // latest-wins: loop picks up the newest window
        scorerJob = scope.launch {
            while (pendingWindow != null && running) {
                val w = pendingWindow
                pendingWindow = null
                if (w.isNullOrBlank()) continue
                _ui.update { it.copy(stage = Stage.SCORING, groqPending = true) }
                val s = settings.current()
                val verdict = scorer.score(
                    settings.effectiveGroqKey(), s.groqModel, s.uiLang,
                    w, displayedRisk, pendingSource
                )
                _ui.update { it.copy(groqPending = false) }
                if (verdict != null) applyVerdict(verdict, smooth = true)
                val gc = scorer.counters()
                _ui.update {
                    it.copy(groq = GroqDiag(gc.requestsSent, gc.lastLatencyMs, gc.tokensIn, gc.tokensOut, gc.lastError))
                }
            }
        }
    }

    private fun applyVerdict(v: RiskVerdict, smooth: Boolean) {
        val target = v.risk.coerceIn(0, 100)
        displayedRisk = if (!smooth || displayedRisk == null) {
            target
        } else if (target >= 80) {
            target // jump immediately on high risk
        } else {
            (displayedRisk!! + (0.5 * (target - displayedRisk!!)).toInt())
        }
        _ui.update {
            it.copy(
                risk = displayedRisk, verdict = v.verdict, reasons = v.reasons,
                tactics = v.tactics, advice = v.advice,
                stage = Stage.RESULT,
                statusText = "Listening – risk ${displayedRisk}% (${v.verdict.name})"
            )
        }
        if (displayedRisk!! >= 80 && !alertedHigh) {
            alertedHigh = true
            _highRisk.tryEmit(v.copy(risk = displayedRisk!!))
        }
    }

    private suspend fun finishFileOrDemo(doneText: String) {
        // Final flush score if the tail never triggered one.
        val snap = aggregator.snapshot()
        if (snap.wordCount >= aggregator.minWords && _ui.value.risk == null && !_ui.value.isSimulation) {
            val s = settings.current()
            scorer.score(settings.effectiveGroqKey(), s.groqModel, s.uiLang, snap.rollingWindow, null, _ui.value.let {
                when (it.mode) {
                    ListenMode.FILE -> AudioSource.FILE
                    else -> AudioSource.SIMULATION
                }
            })?.let { applyVerdict(it, smooth = false) }
        }
        setStatus(Stage.RESULT, doneText)
        endSession(save = true)
    }

    private suspend fun endSession(save: Boolean) {
        val st = _ui.value
        try { deepgram.close() } catch (_: Exception) {}
        try { capture.stop() } catch (_: Exception) {}
        releaseWake()
        if (save && (st.transcriptFull.isNotBlank() || st.risk != null)) {
            val modeName = when (st.mode) {
                ListenMode.LISTENER -> "LISTENER"
                ListenMode.FILE -> "FILE"
                ListenMode.DEMO -> "DEMO"
            }
            val source = when (st.mode) {
                ListenMode.LISTENER -> AudioSource.LIVE
                ListenMode.FILE -> AudioSource.FILE
                ListenMode.DEMO -> AudioSource.SIMULATION
            }
            withContext(Dispatchers.IO) {
                history.save(
                    HistoryRepository.build(
                        modeName, source, st.verdict, st.risk, st.transcriptFull,
                        st.reasons, st.advice, startedAtMs
                    )
                )
            }
        }
        session = null
        _ui.update { it.copy(running = false) }
    }

    private fun resetSession(mode: ListenMode, source: AudioSource, isSimulation: Boolean) {
        aggregator.reset()
        vad.reset()
        pendingWindow = null
        displayedRisk = null
        alertedHigh = false
        silenceSecs = 0.0
        levelHist.clear()
        _ui.update {
            UiState(
                mode = mode, running = true, stage = Stage.STARTING,
                statusText = "Starting…", isSimulation = isSimulation,
                device = DeviceDiag(inCall = callMonitor.isInCall.value),
                inCall = callMonitor.isInCall.value
            )
        }
        @Suppress("UNUSED_EXPRESSION") source
    }

    private fun setStage(stage: Stage, text: String) {
        _ui.update { it.copy(stage = stage, statusText = text) }
    }

    private fun setStatus(stage: Stage, text: String) = setStage(stage, text)

    private fun acquireWake() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @SuppressLint("InvalidWakeLockTag")
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceGuard:session").apply {
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ------------------------------------------------------------- self-test

    suspend fun runSelfTest(): List<SelfTestResult> {
        if (running) return listOf(SelfTestResult("Self-test", false, "Stop the current session first."))
        _ui.update { it.copy(selfTestRunning = true, selfTest = emptyList()) }
        val out = mutableListOf<SelfTestResult>()
        out.add(testMicrophone())
        out.add(testLoopback())
        out.add(testGroq())
        out.add(testDeepgram())
        withContext(Dispatchers.IO) {
            SettingsRepository.stampSelfTest(appContext, System.currentTimeMillis())
        }
        _ui.update { it.copy(selfTestRunning = false, selfTest = out.toList()) }
        return out
    }

    private suspend fun testMicrophone(): SelfTestResult = withContext(Dispatchers.IO) {
        val src = capture.start()
        if (src == null) {
            return@withContext SelfTestResult(
                "Microphone 3s", false,
                capture.error.value ?: "Microphone did not start (permission?)"
            )
        }
        return@withContext try {
            val until = System.currentTimeMillis() + 3000
            var windows = 0
            var silentWindows = 0
            var worst: com.voiceguard.audio.AudioHealth? = null
            val job = scope.launch(Dispatchers.IO) {
                capture.chunks.collect { c ->
                    val h = AudioHealthAnalyzer.analyze(AudioHealthAnalyzer.bytesToSamples(c.bytes))
                    windows++
                    if (h.classification == HealthClass.DIGITAL_SILENCE) silentWindows++
                    worst = h
                }
            }
            while (System.currentTimeMillis() < until) delay(200)
            job.cancel()
            capture.stop()
            if (windows == 0) {
                SelfTestResult("Microphone 3s", false, "No audio frames arrived from $src.")
            } else if (silentWindows == windows) {
                SelfTestResult(
                    "Microphone 3s", false,
                    "All $windows windows were DIGITAL_SILENCE on $src — mic blocked or muted. " +
                        "If this phone is in a call, that is expected: use a second phone."
                )
            } else {
                SelfTestResult(
                    "Microphone 3s", true,
                    "$src delivered $windows windows, RMS ${"%.1f".format(worst?.rmsDb ?: 0.0)} dBFS."
                )
            }
        } catch (t: Throwable) {
            try { capture.stop() } catch (_: Exception) {}
            SelfTestResult("Microphone 3s", false, t.message ?: "failed")
        }
    }

    private suspend fun testLoopback(): SelfTestResult = withContext(Dispatchers.IO) {
        val src = capture.start()
        if (src == null) return@withContext SelfTestResult("Speaker→mic loop", false, "Mic did not start.")
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val prevSpeaker = am?.isSpeakerphoneOn ?: false
        var track: AudioTrack? = null
        return@withContext try {
            // 1 s ambient floor.
            val floor = measureMaxRms(1000)
            // 2 s 1 kHz tone through the speaker while recording.
            try { am?.isSpeakerphoneOn = true } catch (_: Exception) {}
            track = makeToneTrack()
            track?.play()
            val toneJob = scope.launch(Dispatchers.IO) {
                val sine = sineBytes()
                val end = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < end) {
                    try { track?.write(sine, 0, sine.size) } catch (_: Exception) { break }
                }
            }
            val withTone = measureMaxRms(2200)
            toneJob.cancel()
            try { track?.stop() } catch (_: Exception) {}
            capture.stop()
            val gain = withTone - floor
            if (gain > 10.0) {
                SelfTestResult("Speaker→mic loop", true, "Tone raised mic level by ${"%.1f".format(gain)} dB.")
            } else {
                SelfTestResult(
                    "Speaker→mic loop", false,
                    "Tone barely registered (+${"%.1f".format(gain)} dB). Volume low, or mic blocked."
                )
            }
        } catch (t: Throwable) {
            try { track?.release() } catch (_: Exception) {}
            try { capture.stop() } catch (_: Exception) {}
            SelfTestResult("Speaker→mic loop", false, t.message ?: "failed")
        } finally {
            try { am?.isSpeakerphoneOn = prevSpeaker } catch (_: Exception) {}
        }
    }

    private suspend fun measureMaxRms(ms: Long): Double {
        var best = Double.NEGATIVE_INFINITY
        val until = System.currentTimeMillis() + ms
        val job = scope.launch(Dispatchers.IO) {
            capture.chunks.collect { c ->
                val db = AudioHealthAnalyzer.frameRmsDb(c.bytes)
                if (db > best) best = db
            }
        }
        while (System.currentTimeMillis() < until) delay(150)
        job.cancel()
        return best
    }

    private fun makeToneTrack(): AudioTrack {
        val rate = 16000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 6400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun sineBytes(): ByteArray {
        val n = 1600 // 100 ms @16k
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2.0 * PI * 1000.0 * i / 16000.0) * 20000).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private suspend fun testGroq(): SelfTestResult {
        val key = settings.effectiveGroqKey()
        if (key.isBlank()) return SelfTestResult("Groq key", false, "Groq API key is empty. Add it in Settings.")
        val s = settings.current()
        return try {
            val v = scorer.score(key, s.groqModel, s.uiLang, "Hello, see you tomorrow at three.", null, AudioSource.LIVE)
            if (v == null) {
                SelfTestResult("Groq key", false, scorer.counters().lastError ?: "No verdict returned.")
            } else {
                SelfTestResult("Groq key", true, "Model ${s.groqModel} replied risk=${v.risk} ${v.verdict}.")
            }
        } catch (t: Throwable) {
            SelfTestResult("Groq key", false, t.message ?: "failed")
        }
    }

    private suspend fun testDeepgram(): SelfTestResult {
        val key = settings.effectiveDeepgramKey()
        if (key.isBlank()) return SelfTestResult("Deepgram socket", false, "Deepgram key is empty. Add it in Settings.")
        val s = settings.current()
        return try {
            deepgram.connect(
                DeepgramStreamingClient.Config(
                    key, settings.effectiveDeepgramModel(s.deepgramModel),
                    "en", false, false, AudioSource.LIVE
                )
            )
            val until = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < until) {
                when (deepgram.state.value) {
                    DeepgramConnState.OPEN -> {
                        deepgram.close()
                        return SelfTestResult("Deepgram socket", true, "Handshake OK (auth accepted).")
                    }
                    DeepgramConnState.AUTH_ERROR -> {
                        deepgram.close()
                        return SelfTestResult("Deepgram socket", false, "Server rejected the key (401/403).")
                    }
                    else -> delay(250)
                }
            }
            deepgram.close()
            SelfTestResult("Deepgram socket", false, deepgram.counters().lastError ?: "Handshake timed out.")
        } catch (t: Throwable) {
            try { deepgram.close() } catch (_: Exception) {}
            SelfTestResult("Deepgram socket", false, t.message ?: "failed")
        }
    }

    fun exportDiagnostics(): String = buildString {
        val st = _ui.value
        appendLine("VoiceGuard diagnostics ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("Device: ${st.device.manufacturer} ${st.device.model} Android ${st.device.android} (SDK ${st.device.sdk}) ROM=${st.device.rom} inCall=${st.device.inCall}")
        appendLine("Audio: source=${st.micSource} silencedByOs=${st.health?.silencedByOs} rms=${"%.1f".format(st.health?.rmsDb ?: Double.NaN)} dBFS maxAbs=${st.health?.maxAbs} nzr=${"%.4f".format(st.health?.nonZeroRatio ?: 0.0)} class=${st.health?.classification} silence=${"%.1f".format(st.silenceSeconds)}s")
        appendLine("VAD: active=${st.vadActive} activeFraction=${"%.2f".format(st.vadFraction)}")
        appendLine("Deepgram: state=${st.deepgramState} req=${st.deepgram.requestId} sent=${"%.1f".format(st.deepgram.secondsSent)}s msgs=${st.deepgram.messages} words=${st.deepgram.words} empty=${st.deepgram.emptyResults} err=${st.deepgram.lastError}")
        appendLine("Groq: requests=${st.groq.requests} latency=${st.groq.lastLatencyMs}ms tokIn=${st.groq.tokensIn} tokOut=${st.groq.tokensOut} err=${st.groq.lastError}")
        appendLine("Session: mode=${st.mode} stage=${st.stage} risk=${st.risk} verdict=${st.verdict}")
        appendLine("--- log ---")
        appendLine(LogBuffer.export())
        appendLine("--- transcript ---")
        appendLine(st.transcriptFull.ifBlank { "(empty)" })
    }

    companion object {
        private const val TAG = "Orchestrator"
    }
}

