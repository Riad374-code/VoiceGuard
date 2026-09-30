package com.voiceguard.di

import android.app.Application
import com.voiceguard.ai.GroqRiskScorer
import com.voiceguard.audio.AudioCaptureEngine
import com.voiceguard.data.HistoryRepository
import com.voiceguard.data.SettingsRepository
import com.voiceguard.demo.DemoScriptRunner
import com.voiceguard.pipeline.PipelineOrchestrator
import com.voiceguard.stt.DeepgramStreamingClient
import com.voiceguard.telephony.CallStateMonitor

/** Manual DI: singletons built once from the Application. */
object ServiceLocator {
    lateinit var app: Application
        private set

    lateinit var settings: SettingsRepository
        private set
    lateinit var history: HistoryRepository
        private set
    lateinit var capture: AudioCaptureEngine
        private set
    lateinit var deepgram: DeepgramStreamingClient
        private set
    lateinit var scorer: GroqRiskScorer
        private set
    lateinit var callMonitor: CallStateMonitor
        private set
    lateinit var demo: DemoScriptRunner
        private set
    lateinit var orchestrator: PipelineOrchestrator
        private set

    fun init(application: Application) {
        if (this::app.isInitialized) return
        app = application
        settings = SettingsRepository(application)
        history = HistoryRepository(application)
        capture = AudioCaptureEngine(application)
        deepgram = DeepgramStreamingClient()
        scorer = GroqRiskScorer()
        callMonitor = CallStateMonitor(application)
        demo = DemoScriptRunner()
        orchestrator = PipelineOrchestrator(application, settings, history, capture, deepgram, scorer, callMonitor, demo)
        callMonitor.start()
    }
}

