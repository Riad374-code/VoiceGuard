package com.voiceguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voiceguard.data.SettingsRepository

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenOem: () -> Unit) {
    val s by vm.settings.collectAsState()
    var dgKey by remember(s) { mutableStateOf(vm.getKeyOverride("deepgram_key")) }
    var groqKey by remember(s) { mutableStateOf(vm.getKeyOverride("groq_key")) }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API keys (override build values; stored encrypted)", fontWeight = FontWeight.Bold)
                TextField(
                    value = dgKey, onValueChange = { dgKey = it },
                    label = { Text("Deepgram API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = groqKey, onValueChange = { groqKey = it },
                    label = { Text("Groq API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    vm.setKeyOverride("deepgram_key", dgKey.trim())
                    vm.setKeyOverride("groq_key", groqKey.trim())
                }) { Text("Save keys") }
                Text(
                    "Production warning: keys in the app can be extracted. Proxy them through your backend.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Speech-to-text", fontWeight = FontWeight.Bold)
                TextField(
                    value = s.deepgramModel.ifBlank { "(build default)" },
                    onValueChange = { vm.updateSettings { c -> c.copy(deepgramModel = it) } },
                    label = { Text("Deepgram model") },
                    supportingText = { Text("Options: ${SettingsRepository.SUPPORTED_DG_MODELS.joinToString()}") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = s.deepgramLang,
                    onValueChange = { vm.updateSettings { c -> c.copy(deepgramLang = it) } },
                    label = { Text("Language") },
                    supportingText = { Text("Options: ${SettingsRepository.SUPPORTED_STT_LANGS.joinToString()}") },
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("Auto-detect language", s.autoDetect) {
                    vm.updateSettings { c -> c.copy(autoDetect = it) }
                }
                SwitchRow("Speaker diarization", s.diarize) {
                    vm.updateSettings { c -> c.copy(diarize = it) }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scoring", fontWeight = FontWeight.Bold)
                TextField(
                    value = s.groqModel,
                    onValueChange = { vm.updateSettings { c -> c.copy(groqModel = it) } },
                    label = { Text("Groq model") },
                    supportingText = { Text("Options: ${SettingsRepository.SUPPORTED_GROQ_MODELS.joinToString()}") },
                    modifier = Modifier.fillMaxWidth()
                )
                var minWords by remember(s) { mutableStateOf(s.minWords.toString()) }
                TextField(
                    value = minWords,
                    onValueChange = {
                        minWords = it
                        it.toIntOrNull()?.let { n ->
                            if (n in 1..20) vm.updateSettings { c -> c.copy(minWords = n) }
                        }
                    },
                    label = { Text("MIN_WORDS") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("VAD sensitivity: ${"%.2f".format(s.sensitivity)}")
                Slider(
                    value = s.sensitivity,
                    onValueChange = { vm.updateSettings { c -> c.copy(sensitivity = it) } }
                )
                TextField(
                    value = s.uiLang,
                    onValueChange = { vm.updateSettings { c -> c.copy(uiLang = it) } },
                    label = { Text("UI language (reasons language)") },
                    supportingText = { Text("Options: ${SettingsRepository.SUPPORTED_UI_LANGS.joinToString()}") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modes", fontWeight = FontWeight.Bold)
                SwitchRow("File fast mode (~4× realtime)", s.fileFastMode) {
                    vm.updateSettings { c -> c.copy(fileFastMode = it) }
                }
                SwitchRow("VAD for files (default: bypass)", s.vadForFiles) {
                    vm.updateSettings { c -> c.copy(vadForFiles = it) }
                }
                SwitchRow("Demo offline scorer (no network)", s.demoOffline) {
                    vm.updateSettings { c -> c.copy(demoOffline = it) }
                }
                SwitchRow("In-app VoIP source (stub)", s.voipEnabled) {
                    vm.updateSettings { c -> c.copy(voipEnabled = it) }
                }
                SwitchRow("Dark theme", s.darkTheme) {
                    vm.updateSettings { c -> c.copy(darkTheme = it) }
                }
            }
        }

        OutlinedButton(onClick = onOpenOem, modifier = Modifier.fillMaxWidth()) {
            Text("Device setup (battery / Honor / Xiaomi)")
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

