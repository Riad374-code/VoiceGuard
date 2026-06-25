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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.guardvoice.data.CallSession
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.audioDurationLabel
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.BreathingWave
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.RiskMeter
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.StatusPill
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.model.riskLevelForVerdict
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun SummaryScreen(onNavigate: (AppDestination) -> Unit) {
    val context = LocalContext.current
    val sessions by CallSessionRepository.observe(context).collectAsState()
    val latestSession = sessions.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                SectionLabel(text = "Call result")
                Text(
                    text = latestSession?.phoneNumber?.ifBlank { "Incoming caller" }
                        ?: "No completed call analysis yet.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = latestSession?.summary
                        ?: "After the AI analyzer is connected, this screen will show the final verdict, reasons, transcript summary, and actions for the detected caller.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                if (latestSession == null) {
                    EmptyResultPanel()
                } else {
                    SessionResultPanel(session = latestSession)
                }
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Next actions")
                Text(
                    text = "Block, report, delete transcript, and backend sync actions can attach to this saved session record.",
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
private fun SessionResultPanel(session: CallSession) {
    val riskLevel = riskLevelForVerdict(session.verdict)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        StatusPill(
            text = riskLevel.label,
            riskLevel = riskLevel,
            isLive = false
        )
        RiskMeter(score = session.riskScore, riskLevel = riskLevel)
        Text(
            text = "Streamed audio: ${audioDurationLabel(session.audioBytesStreamed)} across ${session.audioChunksStreamed} chunks",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.Ink
        )
        Text(
            text = session.transcriptPreview.ifBlank {
                "No transcript yet. The app saved the call session and audio progress; AI processing is intentionally skipped for now."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.InkMuted
        )
        if (session.reasons.isNotEmpty()) {
            session.reasons.forEach { reason ->
                Text(
                    text = "- $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardColors.InkMuted
                )
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
