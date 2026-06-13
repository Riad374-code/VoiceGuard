package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
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
