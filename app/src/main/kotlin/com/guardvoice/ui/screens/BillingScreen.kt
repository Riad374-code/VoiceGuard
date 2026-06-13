package com.guardvoice.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.model.BillingPlan
import com.guardvoice.ui.model.PlanTier
import com.guardvoice.ui.model.billingPlans
import com.guardvoice.ui.model.billingUsage
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun BillingScreen() {
    var selectedTierName by rememberSaveable { mutableStateOf(PlanTier.Free.name) }
    val selectedTier = PlanTier.valueOf(selectedTierName)
    val selectTier: (PlanTier) -> Unit = { tier -> selectedTierName = tier.name }

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Billing")
                Text(
                    text = "Plan and usage controls.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "Selected tier: ${selectedTier.label}. Real purchases should use Google Play Billing and must not store payment details in the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                BillingUsagePanel()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
            billingPlans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    isSelected = plan.tier == selectedTier,
                    onSelect = { selectTier(plan.tier) }
                )
            }
        }
    }
}

@Composable
private fun BillingUsagePanel() {
    val usage = billingUsage
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.SurfaceMuted)
            .padding(GuardSpace.Large),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "Analyzed minutes",
                    style = MaterialTheme.typography.labelMedium,
                    color = GuardColors.InkMuted
                )
                Text(
                    text = usage.analyzedMinutes.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
            }
            Text(
                text = usage.renewalLabel,
                style = MaterialTheme.typography.labelMedium,
                color = GuardColors.InkMuted
            )
        }
        Text(
            text = "Included minutes will be shown after products are configured.",
            style = MaterialTheme.typography.bodySmall,
            color = GuardColors.InkMuted
        )
    }
}

@Composable
private fun PlanCard(
    plan: BillingPlan,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    AppSurface {
        Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = plan.tier.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = GuardColors.Ink
                        )
                        when {
                            plan.isCurrent -> CurrentPlanBadge(text = "Current")
                            isSelected -> CurrentPlanBadge(text = "Selected")
                        }
                    }
                    Text(
                        text = plan.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GuardColors.InkMuted
                    )
                }
                Text(
                    text = plan.price,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Forest
                )
            }
            FeatureList(features = plan.features)
            if (plan.isCurrent) {
                SecondaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Current plan",
                    onClick = onSelect
                )
            } else {
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (isSelected) "Selected for setup" else "Select tier",
                    onClick = onSelect
                )
            }
        }
    }
}

@Composable
private fun CurrentPlanBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(GuardColors.ForestSoft)
            .border(1.dp, GuardColors.Forest.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = GuardColors.Forest
        )
    }
}

@Composable
private fun FeatureList(features: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Small)) {
        features.forEach { feature ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .clip(CircleShape)
                        .background(GuardColors.Forest)
                        .padding(3.dp)
                )
                Text(
                    text = feature,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.Ink
                )
            }
        }
    }
}
