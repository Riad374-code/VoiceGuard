package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.BreathingWave
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun SummaryScreen(onNavigate: (AppDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                SectionLabel(text = "Call result")
                Text(
                    text = "No completed call analysis yet.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "After the call services and AI analyzer are connected, this screen will show the final verdict, reasons, transcript summary, and actions for the detected unsaved number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                EmptyResultPanel()
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Next actions")
                Text(
                    text = "Block, report, delete transcript, and save-contact actions are reserved here for the business logic phase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
                    SecondaryAction(
                        modifier = Modifier.weight(1f),
                        text = "Dashboard",
                        onClick = { onNavigate(AppDestination.Dashboard) }
                    )
                    PrimaryAction(
                        modifier = Modifier.weight(1f),
                        text = "Preview popup",
                        onClick = { onNavigate(AppDestination.Overlay) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyResultPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        BreathingWave()
        Text(
            text = "Waiting for real call data",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = GuardColors.Ink
        )
        Text(
            text = "No caller number, transcript, prediction, or reason is shown until it is produced by the app runtime.",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.InkMuted
        )
    }
}
