package com.guardvoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.zIndex
import com.guardvoice.account.AccountProfile
import com.guardvoice.ui.clickableWithoutRipple
import com.guardvoice.ui.model.PlanTier
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

private val ACCOUNT_MENU_WIDTH = 270.dp

@Composable
fun AccountMenu(
    profile: AccountProfile?,
    onOpenAccount: () -> Unit,
    onOpenPlans: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier.zIndex(2f),
        contentAlignment = Alignment.TopEnd
    ) {
        AccountMenuButton(
            label = profile?.fullName?.firstOrNull()?.uppercase() ?: "Me",
            onClick = { isExpanded = !isExpanded }
        )
        if (isExpanded) {
            AccountMenuPanel(
                profile = profile,
                onOpenAccount = {
                    isExpanded = false
                    onOpenAccount()
                },
                onOpenPlans = {
                    isExpanded = false
                    onOpenPlans()
                },
                onLogout = {
                    isExpanded = false
                    onLogout()
                }
            )
        }
    }
}

@Composable
private fun AccountMenuButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(GuardColors.Ink)
            .clickableWithoutRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = GuardColors.Surface
        )
    }
}

@Composable
private fun AccountMenuPanel(
    profile: AccountProfile?,
    onOpenAccount: () -> Unit,
    onOpenPlans: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(top = 50.dp)
            .width(ACCOUNT_MENU_WIDTH)
            .clip(RoundedCornerShape(GuardRadius.Large))
            .background(GuardColors.Surface)
            .border(1.dp, GuardColors.Line, RoundedCornerShape(GuardRadius.Large))
            .padding(GuardSpace.Medium),
        verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)
    ) {
        AccountMenuHeader(profile = profile)
        SmallDivider()
        SecondaryAction(
            modifier = Modifier.fillMaxWidth(),
            text = if (profile == null) "Login or register" else "Profile and data",
            onClick = onOpenAccount
        )
        SecondaryAction(
            modifier = Modifier.fillMaxWidth(),
            text = "Subscription plans",
            onClick = onOpenPlans
        )
        if (profile != null) {
            SecondaryAction(
                modifier = Modifier.fillMaxWidth(),
                text = "Log out",
                onClick = onLogout
            )
        }
    }
}

@Composable
private fun AccountMenuHeader(profile: AccountProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.XSmall)) {
        Text(
            text = profile?.fullName ?: "Guest account",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = GuardColors.Ink
        )
        Text(
            text = profile?.email ?: "Login to manage your profile.",
            style = MaterialTheme.typography.bodySmall,
            color = GuardColors.InkMuted
        )
        Text(
            text = "Subscription: ${profile?.planTier?.label ?: PlanTier.Free.label}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = GuardColors.Forest
        )
    }
}
