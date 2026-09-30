package com.voiceguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceguard.pipeline.SelfTestResult
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        DiagCard("Device") {
            Kv("Manufacturer", ui.device.manufacturer)
            Kv("Model", ui.device.model)
            Kv("Android", "${ui.device.android} (SDK ${ui.device.sdk})")
            Kv("ROM", ui.device.rom)
            Kv("In call", ui.device.inCall.toString())
        }
        DiagCard("Audio") {
            Kv("Source", ui.micSource)
            Kv("RMS", "%.1f dBFS".format(ui.health?.rmsDb ?: Double.NaN))
            Kv("maxAbs", "${ui.health?.maxAbs}")
            Kv("nonZeroRatio", "%.4f".format(ui.health?.nonZeroRatio ?: 0.0))
            Kv("speechBand", "%.2f".format(ui.health?.speechBandRatio ?: 0.0))
            Kv("silencedByOs", "${ui.health?.silencedByOs}")
            Kv("class", "${ui.health?.classification}")
            Kv("silence", "%.1fs".format(ui.silenceSeconds))
        }
        DiagCard("VAD") {
            Kv("active", ui.vadActive.toString())
            Kv("activeFraction", "%.2f".format(ui.vadFraction))
        }
        DiagCard("Deepgram") {
            Kv("state", ui.deepgramState.name)
            Kv("request_id", ui.deepgram.requestId.ifBlank { "—" })
            Kv("secondsSent", "%.1f".format(ui.deepgram.secondsSent))
            Kv("messages", "${ui.deepgram.messages}")
            Kv("words", "${ui.deepgram.words}")
            Kv("emptyResults", "${ui.deepgram.emptyResults}")
            Kv("lastError", ui.deepgram.lastError ?: "—")
        }
        DiagCard("Groq") {
            Kv("requests", "${ui.groq.requests}")
            Kv("lastLatency", "${ui.groq.lastLatencyMs} ms")
            Kv("tokensIn/Out", "${ui.groq.tokensIn}/${ui.groq.tokensOut}")
            Kv("lastError", ui.groq.lastError ?: "—")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.runSelfTest() },
                enabled = !ui.selfTestRunning && !ui.running
            ) { Text(if (ui.selfTestRunning) "Testing…" else "Run self-test") }
            OutlinedButton(onClick = {
                scope.launch {
                    val uri = vm.shareDiagnostics(context)
                    context.startActivity(android.content.Intent.createChooser(vm.shareIntent(context, uri), "Share diagnostics"))
                }
            }) { Text("Share log") }
        }
        ui.selfTest.forEach { r -> SelfTestRow(r) }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v)
    }
}

@Composable
private fun SelfTestRow(r: SelfTestResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (if (r.passed) "PASS — " else "FAIL — ") + r.name,
                fontWeight = FontWeight.Bold,
                color = if (r.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(r.detail)
        }
    }
}

