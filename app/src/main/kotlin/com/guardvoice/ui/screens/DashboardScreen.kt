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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardvoice.data.CallSessionRepository
import com.guardvoice.data.CallSessionStatus
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
import com.guardvoice.ui.model.callInsightsFromSessions
import com.guardvoice.ui.model.predictionSummaryFromSessions
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun DashboardScreen(onNavigate: (AppDestination) -> Unit) {
    val context = LocalContext.current
    val sessions by CallSessionRepository.observe(context).collectAsState()
    val predictionSummary = predictionSummaryFromSessions(sessions)
    val detectedCalls = callInsightsFromSessions(sessions)
    val hasActiveCall = sessions.any { session ->
        session.status == CallSessionStatus.Detected || session.status == CallSessionStatus.Listening
    }

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                DashboardHeader(
                    totalSessions = sessions.size,
                    hasActiveCall = hasActiveCall
                )
                PredictionOverview(
                    summary = predictionSummary,
                    totalSessions = sessions.size
                )
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Preview call popup",
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
private fun DashboardHeader(totalSessions: Int, hasActiveCall: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel(text = "User dashboard")
            Text(
                text = if (totalSessions == 0) {
                    "No call sessions yet."
                } else {
                    "$totalSessions call sessions tracked."
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = GuardColors.Ink
            )
            Text(
                text = "Incoming calls, popup choices, microphone streaming status, and future AI verdicts use the same local history.",
                style = MaterialTheme.typography.bodyMedium,
                color = GuardColors.InkMuted
            )
        }
        StatusPill(
            text = if (hasActiveCall) "Live" else "Idle",
            riskLevel = if (hasActiveCall) RiskLevel.Pending else RiskLevel.Safe,
            isLive = hasActiveCall
        )
    }
}

@Composable
private fun PredictionOverview(summary: PredictionSummary, totalSessions: Int) {
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
        if (totalSessions == 0) {
            EmptyState(
                title = "Prediction history is empty",
                body = "Once a user allows tracking on a call, verdicts will be grouped here by safe, risky, and scam."
            )
        } else if (summary.totalCount == 0) {
            EmptyState(
                title = "AI verdicts pending",
                body = "Call sessions are being saved now. Safe, risky, and scam counts will update when the analyzer writes verdicts."
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
        SectionLabel(text = "Detected callers")
        if (calls.isEmpty()) {
            EmptyState(
                title = "No detected callers",
                body = "Callers will appear here after Android sends the first incoming call to GuardVoice."
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
            text = "${call.riskLevel.label} ${call.time}",
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
