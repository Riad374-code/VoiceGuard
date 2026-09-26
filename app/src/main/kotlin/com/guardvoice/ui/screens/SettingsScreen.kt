package com.guardvoice.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.guardvoice.stream.StreamSettings
import com.guardvoice.ui.clickableWithoutRipple
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.SmallDivider
import com.guardvoice.ui.model.SettingItem
import com.guardvoice.ui.model.defaultSettings
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun SettingsScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Settings")
                Text(
                    text = "Control what the app can do.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "These switches are UI state only for now. Later they should map to permission checks, service behavior, and local storage policy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
            }
        }

        SettingsGroup()
        BackendServerPanel()
        SensitivityPanel()
    }
}

@Composable
private fun SettingsGroup() {
    val settings = remember {
        mutableStateListOf(*defaultSettings.toTypedArray())
    }
    AppSurface {
        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
            SectionLabel(text = "Call behavior")
            settings.forEachIndexed { index, item ->
                SettingRow(
                    item = item,
                    onToggle = { isEnabled ->
                        settings[index] = item.copy(isEnabled = isEnabled)
                    }
                )
                if (index != settings.lastIndex) {
                    SmallDivider()
                }
            }
        }
    }
}

@Composable
private fun BackendServerPanel() {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(StreamSettings.getBackendWsUrl(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun onSave() {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            error = "URL cannot be empty"
            return
        }
        // Allow bare host:port — StreamSettings normalizes.
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://") &&
            !trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            !trimmed.contains('.') && !trimmed.contains(':')
        ) {
            error = "Enter a valid URL, e.g. wss://your-server.com"
            return
        }
        StreamSettings.saveBackendWsUrl(context, trimmed)
        val normalized = StreamSettings.getBackendWsUrl(context)
        url = normalized
        error = null
        Toast.makeText(context, "Saved: $normalized", Toast.LENGTH_SHORT).show()
    }

    AppSurface {
        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
            SectionLabel(text = "Analysis server")
            Text(
                text = "Where call audio is streamed — 5-sec windows with 1-sec overlap via Deepgram STT + Groq instant scoring. Only 1-sec overlap PCM is kept in RAM, raw voice is never saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.InkMuted
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                },
                label = { Text("Backend WebSocket URL", style = MaterialTheme.typography.labelMedium) },
                placeholder = { Text("wss://your-server.com/ws/audio-stream", style = MaterialTheme.typography.bodySmall, color = GuardColors.InkMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(GuardRadius.Medium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GuardColors.Navy,
                    unfocusedBorderColor = GuardColors.SurfaceMuted,
                    focusedLabelColor = GuardColors.Navy,
                    cursorColor = GuardColors.Navy
                ),
                isError = error != null,
                supportingText = {
                    Text(
                        text = error ?: "Emulator: ws://10.0.2.2:4000/ws/audio-stream  •  Real device: wss://your-backend.com/ws/audio-stream",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) GuardColors.Rose else GuardColors.InkMuted
                    )
                }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Save chip
                Text(
                    modifier = Modifier
                        .clip(RoundedCornerShape(GuardRadius.Medium))
                        .background(GuardColors.Navy)
                        .padding(horizontal = GuardSpace.Large, vertical = GuardSpace.Medium)
                        .clickableWithoutRipple { onSave() },
                    text = "Save",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GuardColors.Surface
                )
                Text(
                    modifier = Modifier
                        .clip(RoundedCornerShape(GuardRadius.Medium))
                        .background(GuardColors.SurfaceMuted)
                        .padding(horizontal = GuardSpace.Large, vertical = GuardSpace.Medium)
                        .clickableWithoutRipple {
                            StreamSettings.resetToDefault(context)
                            url = StreamSettings.getBackendWsUrl(context)
                            error = null
                            Toast.makeText(context, "Reset to build default", Toast.LENGTH_SHORT).show()
                        },
                    text = "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GuardColors.InkMuted
                )
            }
            Text(
                text = "Applies on next call. Deploy your backend (Node npm start with GEMINI_API_KEY=.env), then paste its wss:// URL here before sharing the APK.",
                style = MaterialTheme.typography.bodySmall,
                color = GuardColors.InkMuted
            )
        }
    }
}

@Composable
private fun SettingRow(
    item: SettingItem,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GuardColors.Ink
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.InkMuted
            )
        }
        Switch(
            checked = item.isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GuardColors.Surface,
                checkedTrackColor = GuardColors.Forest,
                uncheckedThumbColor = GuardColors.InkMuted,
                uncheckedTrackColor = GuardColors.SurfaceMuted
            )
        )
    }
}

@Composable
private fun SensitivityPanel() {
    var sensitivityLabel by rememberSaveable { mutableStateOf("Balanced") }
    AppSurface {
        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
            SectionLabel(text = "Prediction sensitivity")
            Text(
                text = sensitivityLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = GuardColors.Ink
            )
            Text(
                text = "Balanced is the default UI choice. Strict and relaxed modes can be mapped to model thresholds after the analyzer exists.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.InkMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
                SensitivityChip(
                    label = "Relaxed",
                    isSelected = sensitivityLabel == "Relaxed",
                    onClick = { sensitivityLabel = "Relaxed" },
                    modifier = Modifier.weight(1f)
                )
                SensitivityChip(
                    label = "Balanced",
                    isSelected = sensitivityLabel == "Balanced",
                    onClick = { sensitivityLabel = "Balanced" },
                    modifier = Modifier.weight(1f)
                )
                SensitivityChip(
                    label = "Strict",
                    isSelected = sensitivityLabel == "Strict",
                    onClick = { sensitivityLabel = "Strict" },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SensitivityChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        modifier = modifier
            .clip(RoundedCornerShape(GuardRadius.Medium))
            .background(if (isSelected) GuardColors.Ink else GuardColors.SurfaceMuted)
            .padding(GuardSpace.Medium)
            .clickableWithoutRipple(onClick),
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (isSelected) GuardColors.Surface else GuardColors.InkMuted
    )
}
