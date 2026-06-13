package com.guardvoice

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.guardvoice.ui.GuardVoiceApp
import com.guardvoice.ui.model.PermissionAction
import com.guardvoice.ui.model.PermissionItem
import com.guardvoice.ui.model.PermissionState
import com.guardvoice.ui.theme.GuardVoiceTheme

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.ANSWER_PHONE_CALLS
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var permissionItems by remember { mutableStateOf(buildPermissionItems()) }
            val runtimePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                permissionItems = buildPermissionItems()
            }
            val settingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                permissionItems = buildPermissionItems()
            }

            GuardVoiceTheme {
                GuardVoiceApp(
                    permissions = permissionItems,
                    onPermissionAction = { action ->
                        when (action) {
                            PermissionAction.RequestRuntimePermissions -> {
                                runtimePermissionLauncher.launch(RUNTIME_PERMISSIONS)
                            }
                            PermissionAction.OpenOverlaySettings -> {
                                settingsLauncher.launch(overlaySettingsIntent())
                            }
                            PermissionAction.OpenCallerIdSettings -> {
                                settingsLauncher.launch(callerIdSettingsIntent())
                            }
                        }
                    }
                )
            }
        }
    }

    private fun buildPermissionItems(): List<PermissionItem> {
        val hasRuntimePermissions = RUNTIME_PERMISSIONS.all { permission ->
            hasPermission(permission)
        }
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val runtimeAction = if (hasRuntimePermissions) null else PermissionAction.RequestRuntimePermissions
        val runtimeActionLabel = if (hasRuntimePermissions) null else "Grant app permissions"

        return listOf(
            runtimePermissionItem(
                title = "Microphone",
                description = "Used only after per-call consent.",
                isReady = hasPermission(Manifest.permission.RECORD_AUDIO),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            ),
            runtimePermissionItem(
                title = "Contacts",
                description = "Skips saved callers automatically.",
                isReady = hasPermission(Manifest.permission.READ_CONTACTS),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            ),
            runtimePermissionItem(
                title = "Phone state",
                description = "Detects incoming calls from unknown numbers.",
                isReady = hasPhonePermissions(),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            ),
            PermissionItem(
                title = "Display over apps",
                description = "Shows the call popup above the dialer.",
                state = permissionState(hasOverlayPermission),
                action = if (hasOverlayPermission) null else PermissionAction.OpenOverlaySettings,
                actionLabel = if (hasOverlayPermission) null else "Open overlay settings"
            ),
            PermissionItem(
                title = "Caller ID role",
                description = "Enabled after the call screening service is added.",
                state = PermissionState.Waiting
            )
        )
    }

    private fun runtimePermissionItem(
        title: String,
        description: String,
        isReady: Boolean,
        action: PermissionAction?,
        actionLabel: String?
    ): PermissionItem =
        PermissionItem(
            title = title,
            description = description,
            state = permissionState(isReady),
            action = action,
            actionLabel = actionLabel
        )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasPhonePermissions(): Boolean =
        hasPermission(Manifest.permission.READ_PHONE_STATE) &&
            hasPermission(Manifest.permission.READ_CALL_LOG) &&
            hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)

    private fun overlaySettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

    private fun callerIdSettingsIntent(): Intent {
        val roleManager = getSystemService(RoleManager::class.java)
        return if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }
}

private fun permissionState(isReady: Boolean): PermissionState =
    if (isReady) PermissionState.Ready else PermissionState.NeedsAction
