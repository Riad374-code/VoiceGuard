package com.guardvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import com.guardvoice.ui.components.BrandHeader
import com.guardvoice.ui.model.AppDestination
import com.guardvoice.ui.screens.BillingScreen
import com.guardvoice.ui.screens.DashboardScreen
import com.guardvoice.ui.screens.OverlayScreen
import com.guardvoice.ui.screens.SetupScreen
import com.guardvoice.ui.screens.SettingsScreen
import com.guardvoice.ui.screens.SummaryScreen
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardSpace

@Composable
fun GuardVoiceApp() {
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Setup.name) }
    val destination = AppDestination.valueOf(destinationName)
    val navigate: (AppDestination) -> Unit = { destinationName = it.name }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = GuardSpace.Large),
            verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)
        ) {
            AppChrome(
                currentDestination = destination,
                onNavigate = navigate
            )
            when (destination) {
                AppDestination.Setup -> SetupScreen(onNavigate = navigate)
                AppDestination.Dashboard -> DashboardScreen(onNavigate = navigate)
                AppDestination.Overlay -> OverlayScreen(onNavigate = navigate)
                AppDestination.Billing -> BillingScreen()
                AppDestination.Settings -> SettingsScreen()
                AppDestination.Summary -> SummaryScreen(onNavigate = navigate)
            }
        }
    }
}

@Composable
private fun AppChrome(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
        BrandHeader()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppDestination.entries.forEach { destination ->
                val isSelected = destination == currentDestination
                Text(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isSelected) GuardColors.Ink else GuardColors.SurfaceMuted
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .then(
                            Modifier.clickableWithoutRipple {
                                onNavigate(destination)
                            }
                        ),
                    text = destination.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) GuardColors.Surface else GuardColors.InkMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
