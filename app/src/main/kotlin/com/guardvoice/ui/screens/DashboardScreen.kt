package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.BreathingWave
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.SmallDivider
import com.guardvoice.ui.components.StatusPill
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.model.CallInsight
import com.guardvoice.ui.model.PredictionSummary
import com.guardvoice.ui.model.RiskLevel
import com.guardvoice.ui.model.detectedCalls
import com.guardvoice.ui.model.predictionSummary
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun DashboardScreen(onNavigate: (AppDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                DashboardHeader()
                PredictionOverview(summary = predictionSummary)
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Preview unsaved call popup",
                    onClick = { onNavigate(AppDestination.Overlay) }
                )
            }
        }

        AppSurface {
            DetectedCallsSection(calls = detectedCalls)
        }
    }
}

@Composable
private fun DashboardHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel(text = "User dashboard")
            Text(
                text = "No unsaved calls detected yet.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = GuardColors.Ink
            )
            Text(
                text = "This dashboard will populate from CallScreeningService after detection logic is connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.InkMuted
            )
        }
        StatusPill(
            text = "Idle",
            riskLevel = RiskLevel.Safe,
            isLive = true
        )
    }
}

@Composable
private fun PredictionOverview(summary: PredictionSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)
        ) {
            PredictionTile(
                label = "Safe",
                count = summary.safeCount,
                riskLevel = RiskLevel.Safe,
                modifier = Modifier.weight(1f)
            )
            PredictionTile(
                label = "Risky",
                count = summary.suspiciousCount,
                riskLevel = RiskLevel.Suspicious,
                modifier = Modifier.weight(1f)
            )
            PredictionTile(
                label = "Scam",
                count = summary.scamCount,
                riskLevel = RiskLevel.Scam,
                modifier = Modifier.weight(1f)
            )
        }
        if (summary.totalCount == 0) {
            EmptyState(
                title = "Prediction history is empty",
                body = "Once a user allows tracking on an unsaved call, verdicts will be grouped here by safe, risky, and scam."
            )
        }
    }
}

@Composable
private fun PredictionTile(
    label: String,
    count: Int,
    riskLevel: RiskLevel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(GuardRadius.Medium))
            .background(riskLevel.background)
            .padding(GuardSpace.Medium),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.XSmall)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = riskLevel.color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = riskLevel.color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DetectedCallsSection(calls: List<CallInsight>) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
        SectionLabel(text = "Detected unsaved numbers")
        if (calls.isEmpty()) {
            EmptyState(
                title = "No detected numbers",
                body = "Saved contacts are ignored. Unknown numbers will appear here only after the Android call-screening service is implemented."
            )
            return
        }

        calls.forEachIndexed { index, call ->
            DetectedCallRow(call = call)
            if (index != calls.lastIndex) {
                SmallDivider()
            }
        }
    }
}

@Composable
private fun DetectedCallRow(call: CallInsight) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(call.riskLevel.background)
                .padding(GuardSpace.Small)
        ) {
            Text(
                text = call.riskLevel.label.take(1),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = call.riskLevel.color
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.caller,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GuardColors.Ink
            )
            Text(
                text = call.reason,
                style = MaterialTheme.typography.bodySmall,
                color = GuardColors.InkMuted
            )
        }
        Text(
            text = call.riskLevel.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = call.riskLevel.color
        )
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        BreathingWave()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = GuardColors.Ink
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = GuardColors.InkMuted
        )
    }
}
