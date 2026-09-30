package com.voiceguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceguard.R
import com.voiceguard.pipeline.ListenMode
import com.voiceguard.service.ListeningForegroundService
import com.voiceguard.ui.theme.VoiceGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            VoiceGuardTheme(dark = settings.darkTheme) {
                AppNav(vm)
            }
        }
    }
}

private data class Dest(val id: String, val label: String, val icon: ImageVector)

@Composable
private fun AppNav(vm: MainViewModel) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val settings by vm.settings.collectAsState()
    var dest by remember { mutableStateOf("listen") }
    var showOem by remember { mutableStateOf(false) }
    var showInCallDialog by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }

    fun missingPerms(): List<String> {
        @Suppress("UNUSED_EXPRESSION") permTick
        val out = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            out.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            out.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            out.add(Manifest.permission.READ_PHONE_STATE)
        }
        return out
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            var name = "audio"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
                }
            } catch (_: Exception) {
            }
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.startFile(uri, name)
        }
    }

    fun startListenerService() {
        val missing = missingPerms()
        if (Manifest.permission.RECORD_AUDIO in missing) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (ui.inCall) {
            showInCallDialog = true
            return
        }
        // Android 14: foreground service starts only from this visible tap. Allowed here.
        ContextCompat.startForegroundService(
            context, Intent(context, ListeningForegroundService::class.java)
                .setAction(ListeningForegroundService.ACTION_START)
        )
    }

    if (!settings.consentAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.consent_title)) },
            text = { Text(stringResource(R.string.consent_text)) },
            confirmButton = {
                TextButton(onClick = { vm.acceptConsent() }) {
                    Text(stringResource(R.string.consent_accept))
                }
            }
        )
    }

    if (showInCallDialog) {
        AlertDialog(
            onDismissRequest = { showInCallDialog = false },
            title = { Text("In a call") },
            text = { Text(stringResource(R.string.phone_in_call_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showInCallDialog = false
                    filePicker.launch(arrayOf("audio/*"))
                }) { Text("Switch to File Mode") }
            },
            dismissButton = {
                TextButton(onClick = { showInCallDialog = false }) { Text("Close") }
            }
        )
    }

    val dests = listOf(
        Dest("listen", "Listen", Icons.Filled.Mic),
        Dest("diag", "Diagnostics", Icons.Filled.MonitorHeart),
        Dest("settings", "Settings", Icons.Filled.Tune),
        Dest("history", "History", Icons.Filled.History)
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                dests.forEach { d ->
                    NavigationBarItem(
                        selected = dest == d.id,
                        onClick = { dest = d.id },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                showOem -> OemSetupScreen(onBack = { showOem = false })
                dest == "listen" -> MainScreen(
                    vm = vm,
                    missingPerms = missingPerms(),
                    onGrantPerms = { permLauncher.launch(missingPerms().toTypedArray()) },
                    onOpenAppSettings = {
                        context.startActivity(
                            Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        )
                    },
                    onStartListener = { startListenerService() },
                    onPickFile = { filePicker.launch(arrayOf("audio/*")) }
                )
                dest == "diag" -> DiagnosticsScreen(vm)
                dest == "settings" -> SettingsScreen(vm, onOpenOem = { showOem = true })
                dest == "history" -> HistoryScreen(vm)
            }
        }
    }
}

/** True when the user ticked "don't ask again" (or policy blocks the request). */
fun MainActivity.isPermanentlyDenied(perm: String): Boolean =
    ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED &&
        !shouldShowRequestPermissionRationale(perm)

fun currentModeLabel(mode: ListenMode): String = when (mode) {
    ListenMode.LISTENER -> "Listener"
    ListenMode.FILE -> "File"
    ListenMode.DEMO -> "Demo"
}

