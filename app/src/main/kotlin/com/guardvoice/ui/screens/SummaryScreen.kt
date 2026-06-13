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
import androidx.compose.ui.unit.dp
import com.guardvoice.ui.components.AppSurface
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
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun SummaryScreen(onNavigate: (AppDestination) -> Unit) {
    val analysis = demoAnalysis.copy(
        riskLevel = RiskLevel.Scam,
        riskScore = 72,
        transcript = "The caller claimed to represent bank security, demanded immediate verification, and asked for card details before the call ended."
    )

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel(text = "Call result")
                        Text(
                            text = analysis.caller,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = GuardColors.Ink
                        )
                    }
                    StatusPill(
                        text = analysis.riskLevel.label,
                        riskLevel = analysis.riskLevel
                    )
                }
                RiskMeter(
                    score = analysis.riskScore,
                    riskLevel = analysis.riskLevel
                )
                TranscriptLine(text = analysis.transcript)
                ReasonStack(reasons = analysis.reasons)
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Next actions")
                Text(
                    text = "The UI reserves space for blocking and reporting, but these buttons are mock actions until call-log and backend logic are added.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
                    PrimaryAction(
                        modifier = Modifier.weight(1f),
                        text = "Block number",
                        onClick = { onNavigate(AppDestination.Home) }
                    )
                    SecondaryAction(
                        modifier = Modifier.weight(1f),
                        text = "Back home",
                        onClick = { onNavigate(AppDestination.Home) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasonStack(reasons: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
        reasons.forEachIndexed { index, reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GuardRadius.Medium))
                    .background(GuardColors.RoseSoft)
                    .padding(GuardSpace.Medium),
                horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)
            ) {
                Text(
                    text = "0${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Rose
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.Ink
                )
            }
        }
    }
}
