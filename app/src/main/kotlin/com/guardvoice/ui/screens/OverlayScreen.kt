package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.BreathingWave
import com.guardvoice.ui.components.OffsetBadge
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.RiskMeter
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.StatusPill
import com.guardvoice.ui.components.TranscriptLine
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.model.RiskLevel
import com.guardvoice.ui.model.demoAnalysis
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSize
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun OverlayScreen(onNavigate: (AppDestination) -> Unit) {
    var isTrackingAllowed by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Popup style")
                Text(
                    text = "Per-call permission before voice tracking.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "This preview mirrors the floating overlay that appears on incoming calls. It should feel quick, explicit, and hard to mistake for silent recording.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
            }
        }

        PhoneCallStage(
            isTrackingAllowed = isTrackingAllowed,
            onAllowTracking = { isTrackingAllowed = true },
            onDecline = { isTrackingAllowed = false },
            onFinish = { onNavigate(AppDestination.Summary) }
        )
    }
}

@Composable
private fun PhoneCallStage(
    isTrackingAllowed: Boolean,
    onAllowTracking: () -> Unit,
    onDecline: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GuardRadius.XLarge))
            .background(GuardColors.Ink)
            .padding(horizontal = GuardSpace.Medium, vertical = GuardSpace.Large),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)
        ) {
            MockDialerBackground()
            if (isTrackingAllowed) {
                LiveVerdictPopup(onFinish = onFinish)
            } else {
                ConsentPopup(
                    onAllowTracking = onAllowTracking,
                    onDecline = onDecline
                )
            }
        }
    }
}

@Composable
private fun MockDialerBackground() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Small)
    ) {
        Text(
            text = "Incoming call",
            style = MaterialTheme.typography.labelLarge,
            color = GuardColors.Surface.copy(alpha = 0.68f)
        )
        Text(
            text = "Incoming caller",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = GuardColors.Surface
        )
        Text(
            text = "Per-call consent required",
            style = MaterialTheme.typography.bodySmall,
            color = GuardColors.Surface.copy(alpha = 0.54f)
        )
    }
}

@Composable
private fun ConsentPopup(
    onAllowTracking: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(GuardSize.OverlayWidth)
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.Surface)
            .border(1.dp, GuardColors.Line, RoundedCornerShape(GuardRadius.Large))
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        OffsetBadge(text = "GuardVoice check")
        Text(
            text = "Allow voice tracking for this call?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = GuardColors.Ink
        )
        Text(
            text = "Tracking starts only if you allow it. The app may switch to speaker so both sides can be analyzed.",
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.InkMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
            PrimaryAction(
                modifier = Modifier.weight(1f),
                text = "Allow",
                onClick = onAllowTracking
            )
            SecondaryAction(
                modifier = Modifier.weight(1f),
                text = "Skip",
                onClick = onDecline
            )
        }
    }
}

@Composable
private fun LiveVerdictPopup(onFinish: () -> Unit) {
    val analysis = demoAnalysis
    Column(
        modifier = Modifier
            .width(GuardSize.OverlayWidth)
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.Surface)
            .border(1.dp, GuardColors.Line, RoundedCornerShape(GuardRadius.Large))
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(
                text = "Analyzing",
                riskLevel = analysis.riskLevel,
                isLive = true
            )
            Text(
                text = analysis.elapsed,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = GuardColors.InkMuted
            )
        }
        BreathingWave()
        TranscriptLine(text = analysis.transcript)
        RiskMeter(
            score = analysis.riskScore,
            riskLevel = analysis.riskLevel
        )
        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.XSmall)) {
            if (analysis.reasons.isEmpty()) {
                Text(
                    text = "Reasons will appear after enough speech is analyzed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GuardColors.InkMuted
                )
            } else {
                analysis.reasons.forEach { reason ->
                    Text(
                        text = "- $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = GuardColors.InkMuted
                    )
                }
            }
        }
        PrimaryAction(
            modifier = Modifier.fillMaxWidth(),
            text = "End call preview",
            onClick = onFinish
        )
    }
}
