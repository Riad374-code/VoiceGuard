package com.voiceguard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceguard.data.AppSettings
import com.voiceguard.data.SessionRecord
import com.voiceguard.di.ServiceLocator
import com.voiceguard.pipeline.PipelineOrchestrator
import com.voiceguard.pipeline.SelfTestResult
import com.voiceguard.pipeline.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel : ViewModel() {
    private val orchestrator: PipelineOrchestrator get() = ServiceLocator.orchestrator
    private val settingsRepo get() = ServiceLocator.settings
    private val historyRepo get() = ServiceLocator.history

    val ui: StateFlow<UiState> = orchestrator.ui
    val highRisk = orchestrator.highRisk
    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _history = MutableStateFlow<List<SessionRecord>>(emptyList())
    val history: StateFlow<List<SessionRecord>> = _history.asStateFlow()

    init {
        refreshHistory()
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = historyRepo.list()
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.clear()
            _history.value = emptyList()
        }
    }

    fun exportHistoryText(r: SessionRecord): String = historyRepo.exportText(r)

    fun stop() = orchestrator.stop()

    fun startFile(uri: Uri, name: String) = orchestrator.startFile(uri, name)

    fun startDemo(scenarioId: String) = orchestrator.startDemo(scenarioId)

    fun runSelfTest() {
        viewModelScope.launch {
            orchestrator.runSelfTest()
        }
    }

    fun selfTestResults(): List<SelfTestResult> = ui.value.selfTest

    fun acceptConsent() {
        viewModelScope.launch {
            settingsRepo.update { it.copy(consentAccepted = true) }
        }
    }

    fun updateSettings(t: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(t) }
    }

    fun setKeyOverride(which: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setKeyOverride(which, value)
        }
    }

    fun getKeyOverride(which: String): String = settingsRepo.getKeyOverride(which)

    suspend fun shareDiagnostics(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "voiceguard-diagnostics.txt")
        f.writeText(orchestrator.exportDiagnostics())
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
    }

    fun shareIntent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}

