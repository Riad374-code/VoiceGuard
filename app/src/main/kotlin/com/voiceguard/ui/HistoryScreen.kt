package com.voiceguard.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceguard.data.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.clearHistory() }) { Text("Clear") }
        }
        if (history.isEmpty()) {
            Text("No sessions yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history, key = { it.id }) { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${fmtTime(r.startedAtMs)} · ${r.mode} · ${r.source}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Risk ${r.risk?.let { "$it%" } ?: "—"} · ${r.verdict}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                r.transcript.take(220) + if (r.transcript.length > 220) "…" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, vm.exportHistoryText(r))
                                }
                                context.startActivity(Intent.createChooser(intent, "Export session"))
                            }) { Text("Export") }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

