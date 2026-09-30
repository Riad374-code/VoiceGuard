package com.voiceguard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One-time setup checklist for Honor/MagicOS and Xiaomi/MIUI/HyperOS devices:
 * battery unrestricted, autostart, notifications, DND. Every deep link is
 * tried best-effort with a fallback to the app settings page.
 */
@Composable
fun OemSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manu = (Build.MANUFACTURER ?: "").lowercase()
    val isHonor = "honor" in manu || "huawei" in manu
    val isXiaomi = "xiaomi" in manu || "redmi" in manu || "poco" in manu
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Device setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Build.MANUFACTURER=${Build.MANUFACTURER} MODEL=${Build.MODEL}")

        if (isHonor) {
            StepCard(
                "1. Honor: manual app launch",
                "Settings → Apps → App launch → VoiceGuard → Manage manually: allow Auto-launch, Secondary launch, Run in background."
            ) { openAppDetails(context) }
        }
        if (isXiaomi) {
            StepCard(
                "1. Xiaomi: autostart + battery",
                "Security → Autostart: enable VoiceGuard. Settings → Apps → VoiceGuard → Battery saver → No restrictions. Lock VoiceGuard in Recents."
            ) { openAppDetails(context) }
        }
        StepCard(
            "2. Ignore battery optimizations",
            "Lets listening survive with the screen off. (You can revoke this any time.)"
        ) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isIgnoringBatteryOptimizations(context.packageName) == false) {
                    context.startActivity(
                        Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    )
                }
            } catch (_: Exception) {
                openAppDetails(context)
            }
        }
        StepCard(
            "3. Allow notifications",
            "Status notification keeps the microphone session alive; risk alerts arrive here."
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startActivity(
                        Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                } else openAppDetails(context)
            } catch (_: Exception) {
                openAppDetails(context)
            }
        }
        StepCard(
            "4. Do Not Disturb",
            "Add VoiceGuard alerts as an exception (or turn DND off) so high-risk warnings can reach you."
        ) {
            try {
                context.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } catch (_: Exception) {
                openAppDetails(context)
            }
        }
        Text(
            "VoiceGuard never starts recording from the background and never opens windows over calls: " +
                "alerts arrive as a heads-up notification with vibration.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun StepCard(title: String, body: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
            Button(onClick = onOpen) { Text("Open settings") }
        }
    }
}

private fun openAppDetails(context: Context) {
    try {
        context.startActivity(
            Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        )
    } catch (_: Exception) {
    }
}

