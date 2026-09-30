package com.voiceguard.ui

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceguard.R
import com.voiceguard.ai.Verdict
import com.voiceguard.di.ServiceLocator
import com.voiceguard.pipeline.ListenMode

@Composable
fun MainScreen(
    vm: MainViewModel,
    missingPerms: List<String>,
    onGrantPerms: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartListener: () -> Unit,
    onPickFile: () -> Unit
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = context as MainActivity
    var pickedMode by remember { mutableStateOf(ListenMode.LISTENER) }
    var demoScenario by remember { mutableStateOf(ServiceLocator.demo.scenarios[1].id) }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (ui.isSimulation) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF7A1F1F))) {
                Text(
                    stringResource(R.string.simulation_banner),
                    Modifier.padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold
                )
            }
        }
        if (ui.inCall && !ui.running) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Phone is in a call", fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.phone_in_call_msg))
                }
            }
        }
        if (missingPerms.isNotEmpty() && !ui.running) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Permissions needed", fontWeight = FontWeight.Bold)
                    if (Manifest.permission.RECORD_AUDIO in missingPerms) {
                        Text("Microphone — to hear the call speaker in Listener Mode.")
                    }
                    if (Manifest.permission.POST_NOTIFICATIONS in missingPerms) {
                        Text("Notifications — for the listening status and risk alerts.")
                    }
                    if (Manifest.permission.READ_PHONE_STATE in missingPerms) {
                        Text("Phone state — to warn you when this phone is in a call (mic blocked by OS).")
                    }
                    val permanent = missingPerms.any { activity.isPermanentlyDenied(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onGrantPerms) { Text("Grant") }
                        if (permanent) {
                            OutlinedButton(onClick = onOpenAppSettings) { Text("Open settings") }
                        }
                    }
                }
            }
        }

        // Mode selector.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = (if (ui.running) ui.mode else pickedMode) == ListenMode.LISTENER,
                onClick = { if (!ui.running) pickedMode = ListenMode.LISTENER },
                label = { Text("Listener") }
            )
            FilterChip(
                selected = (if (ui.running) ui.mode else pickedMode) == ListenMode.FILE,
                onClick = { if (!ui.running) pickedMode = ListenMode.FILE },
                label = { Text("File") }
            )
            FilterChip(
                selected = (if (ui.running) ui.mode else pickedMode) == ListenMode.DEMO,
                onClick = { if (!ui.running) pickedMode = ListenMode.DEMO },
                label = { Text("Demo") }
            )
        }

        val effectiveMode = if (ui.running) ui.mode else pickedMode
        when (effectiveMode) {
            ListenMode.LISTENER -> {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How to use Listener Mode", fontWeight = FontWeight.Bold)
                        Text("1. Put the call on speaker at high volume on Phone A.")
                        Text("2. Place this phone 10–20 cm from Phone A's speaker.")
                        Text("3. Tap Start listening on this phone.")
                    }
                }
                Button(
                    onClick = { if (ui.running) vm.stop() else onStartListener() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (ui.running) stringResource(R.string.stop) else stringResource(R.string.start_listening))
                }
                Text("Mic source: ${ui.micSource}", fontSize = 12.sp)
            }
            ListenMode.FILE -> {
                if (!ui.running) {
                    Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                        Text("Pick audio file")
                    }
                    Text("Decodes m4a/mp3/wav/amr/3gp to 16 kHz mono and streams it through the same pipeline.")
                } else {
                    Text("File: ${ui.fileName}")
                    ui.fileProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    Button(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stop))
                    }
                }
            }
            ListenMode.DEMO -> {
                if (!ui.running) {
                    ServiceLocator.demo.scenarios.forEach { sc ->
                        FilterChip(
                            selected = demoScenario == sc.id,
                            onClick = { demoScenario = sc.id },
                            label = { Text(sc.title) }
                        )
                    }
                    Button(onClick = { vm.startDemo(demoScenario) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Run demo")
                    }
                    Text("Scripted transcripts feed the real scorer. Results are flagged SIMULATION.")
                } else {
                    Button(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stop))
                    }
                }
            }
        }

        // Level meter.
        LevelMeter(levels = ui.levels)

        // Status.
        AssistChip(onClick = {}, label = { Text(ui.stage.name) })
        Text(ui.statusText)
        if (ui.blockedByOs) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(R.string.no_audio_blocked), Modifier.padding(12.dp))
            }
        }
        ui.noWordsWarning?.let { w ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(w, Modifier.padding(12.dp))
            }
        }

        // Transcript.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Transcript", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(ui.transcriptFull.ifBlank { "(listening…)" })
                if (ui.interim.isNotBlank()) {
                    Text(ui.interim, color = Color.Gray)
                }
            }
        }

        // Risk gauge (null-safe).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Risk", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        ui.risk?.let { "$it%" } ?: "—",
                        fontSize = 40.sp, fontWeight = FontWeight.Bold,
                        color = riskColor(ui.risk)
                    )
                    VerdictChip(ui.verdict)
                }
                if (ui.groqPending) Text("Scoring…")
                ui.reasons.forEach { Text("• $it") }
                if (ui.tactics.isNotEmpty()) Text("Tactics: ${ui.tactics.joinToString()}", fontSize = 12.sp)
                if (ui.advice.isNotBlank()) Text("Advice: ${ui.advice}")
            }
        }
    }
}

@Composable
private fun VerdictChip(v: Verdict) {
    val (label, color) = when (v) {
        Verdict.SAFE -> "SAFE" to Color(0xFF2E7D32)
        Verdict.SUSPICIOUS -> "SUSPICIOUS" to Color(0xFFF9A825)
        Verdict.SCAM -> "SCAM" to Color(0xFFC62828)
        Verdict.UNKNOWN -> "NOT SCORED" to Color.Gray
    }
    AssistChip(onClick = {}, label = { Text(label, color = color, fontWeight = FontWeight.Bold) })
}

private fun riskColor(risk: Int?): Color = when {
    risk == null -> Color.Gray
    risk >= 70 -> Color(0xFFC62828)
    risk >= 30 -> Color(0xFFF9A825)
    else -> Color(0xFF2E7D32)
}

@Composable
private fun LevelMeter(levels: List<Float>) {
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        if (levels.isEmpty()) return@Canvas
        val bw = size.width / 30f
        levels.takeLast(30).forEachIndexed { i, l ->
            val h = (l.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
            drawLine(
                barColor, Offset(i * bw + bw / 2, size.height),
                Offset(i * bw + bw / 2, size.height - h), strokeWidth = bw * 0.6f
            )
        }
    }
}

