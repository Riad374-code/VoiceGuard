package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.RiskMeter
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.SmallDivider
import com.guardvoice.ui.components.StatusPill
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.model.CallInsight
import com.guardvoice.ui.model.RiskLevel
import com.guardvoice.ui.model.recentCalls
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun HomeScreen(onNavigate: (AppDestination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel(text = "Protection console")
                        Text(
                            text = "Ready for unknown callers.",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = GuardColors.Ink
                        )
                    }
                    StatusPill(
                        text = "Live",
                        riskLevel = RiskLevel.Safe,
                        isLive = true
                    )
                }
                StatStrip()
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Simulate incoming call",
                    onClick = { onNavigate(AppDestination.Overlay) }
                )
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Recent calls")
                recentCalls.forEachIndexed { index, call ->
                    RecentCallRow(call = call)
                    if (index != recentCalls.lastIndex) {
                        SmallDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatStrip() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)
    ) {
        StatTile(label = "Checked", value = "18", modifier = Modifier.weight(1f))
        StatTile(label = "Allowed", value = "15", modifier = Modifier.weight(1f))
        StatTile(label = "Flagged", value = "3", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(GuardRadius.Medium))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Medium),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.XSmall)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = GuardColors.Ink
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GuardColors.InkMuted
        )
    }
}

@Composable
private fun RecentCallRow(call: CallInsight) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(GuardRadius.Medium))
                .background(call.riskLevel.background)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = call.riskLevel.label.take(2).uppercase(),
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
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = call.time,
                style = MaterialTheme.typography.labelSmall,
                color = GuardColors.InkMuted
            )
            RiskMeter(
                modifier = Modifier.fillMaxWidth(0.24f),
                score = call.riskScore,
                riskLevel = call.riskLevel
            )
        }
    }
}
