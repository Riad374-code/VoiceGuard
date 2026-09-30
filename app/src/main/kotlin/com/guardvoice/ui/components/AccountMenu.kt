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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.guardvoice.account.AccountProfile
import com.guardvoice.ui.clickableWithoutRipple
import com.guardvoice.ui.model.PlanTier
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardRadius
import com.guardvoice.ui.theme.GuardSpace

private val ACCOUNT_MENU_WIDTH = 270.dp
private val ACCOUNT_MENU_VERTICAL_OFFSET = 50.dp
private val ACCOUNT_MENU_ELEVATION = 12.dp

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
        modifier = modifier,
        contentAlignment = Alignment.TopEnd
    ) {
        AccountMenuButton(
            label = profile?.fullName?.firstOrNull()?.uppercase() ?: "Me",
            onClick = { isExpanded = !isExpanded }
        )
        if (isExpanded) {
            AccountMenuPopup(
                profile = profile,
                onDismiss = { isExpanded = false },
                onOpenAccount = onOpenAccount,
                onOpenPlans = onOpenPlans,
                onLogout = onLogout
            )
        }
    }
}

@Composable
private fun AccountMenuPopup(
    profile: AccountProfile?,
    onDismiss: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPlans: () -> Unit,
    onLogout: () -> Unit
) {
    val verticalOffset = with(LocalDensity.current) {
        ACCOUNT_MENU_VERTICAL_OFFSET.roundToPx()
    }

    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(x = 0, y = verticalOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true
        )
    ) {
        AccountMenuPanel(
            profile = profile,
            onOpenAccount = {
                onDismiss()
                onOpenAccount()
            },
            onOpenPlans = {
                onDismiss()
                onOpenPlans()
            },
            onLogout = {
                onDismiss()
                onLogout()
            }
        )
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
    Surface(
        modifier = Modifier
            .width(ACCOUNT_MENU_WIDTH)
            .border(1.dp, GuardColors.Line, RoundedCornerShape(GuardRadius.Large)),
        shape = RoundedCornerShape(GuardRadius.Large),
        color = GuardColors.Surface,
        shadowElevation = ACCOUNT_MENU_ELEVATION
    ) {
        Column(
            modifier = Modifier.padding(GuardSpace.Medium),
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
