package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.SmallDivider
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.model.PermissionItem
import com.guardvoice.ui.model.PermissionState
import com.guardvoice.ui.model.RiskLevel
import com.guardvoice.ui.model.setupPermissions
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun SetupScreen(onNavigate: (AppDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                SectionLabel(text = "First launch")
                Text(
                    text = "AI call protection that asks first.",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "GuardVoice stays idle for saved contacts. When an unknown caller rings, it asks for consent before any voice tracking starts.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = GuardColors.InkMuted
                )
                BreathingWave()
                Row(horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
                    PrimaryAction(
                        modifier = Modifier.weight(1f),
                        text = "Open console",
                        onClick = { onNavigate(AppDestination.Dashboard) }
                    )
                    SecondaryAction(
                        modifier = Modifier.weight(1f),
                        text = "View popup",
                        onClick = { onNavigate(AppDestination.Overlay) }
                    )
                }
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Setup checklist")
                setupPermissions.forEachIndexed { index, item ->
                    PermissionRow(item = item)
                    if (index != setupPermissions.lastIndex) {
                        SmallDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PermissionDot(state = item.state)
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
        Text(
            modifier = Modifier
                .clip(CircleShape)
                .background(statusBackground(item.state))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            text = statusLabel(item.state),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(item.state),
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun PermissionDot(state: PermissionState) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShapeCompat)
            .background(statusBackground(state))
            .padding(9.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(statusColor(state))
                .padding(5.dp)
        )
    }
}

private val RoundedCornerShapeCompat = androidx.compose.foundation.shape.RoundedCornerShape(
    GuardRadius.Small
)

private fun statusLabel(state: PermissionState): String = when (state) {
    PermissionState.Ready -> "Ready"
    PermissionState.NeedsAction -> "Action"
    PermissionState.Waiting -> "Waiting"
}

private fun statusColor(state: PermissionState) = when (state) {
    PermissionState.Ready -> RiskLevel.Safe.color
    PermissionState.NeedsAction -> RiskLevel.Scam.color
    PermissionState.Waiting -> RiskLevel.Suspicious.color
}

private fun statusBackground(state: PermissionState) = when (state) {
    PermissionState.Ready -> RiskLevel.Safe.background
    PermissionState.NeedsAction -> RiskLevel.Scam.background
    PermissionState.Waiting -> RiskLevel.Suspicious.background
}
