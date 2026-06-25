package com.guardvoice

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.guardvoice.compat.AndroidDeviceCompatibility
import com.guardvoice.ui.GuardVoiceApp
import com.guardvoice.ui.model.PermissionAction
import com.guardvoice.ui.model.PermissionItem
import com.guardvoice.ui.model.PermissionState
import com.guardvoice.ui.theme.GuardVoiceTheme

class MainActivity : ComponentActivity() {
    private var permissionItems by mutableStateOf<List<PermissionItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GuardVoice)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionItems = buildPermissionItems()
        setContent {
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
                                runtimePermissionLauncher.launch(runtimePermissions())
                            }
                            PermissionAction.OpenOverlaySettings -> {
                                launchSettingsIntent(
                                    settingsLauncher,
                                    overlaySettingsIntent(),
                                    appDetailsIntent()
                                )
                            }
                            PermissionAction.OpenCallerIdSettings -> {
                                launchSettingsIntent(
                                    settingsLauncher,
                                    callerIdSettingsIntent(),
                                    appDetailsIntent()
                                )
                            }
                            PermissionAction.OpenBatterySettings -> {
                                launchSettingsIntent(
                                    settingsLauncher,
                                    AndroidDeviceCompatibility.batteryOptimizationIntent(
                                        this@MainActivity
                                    ),
                                    batteryOptimizationListIntent()
                                )
                            }
                            PermissionAction.OpenManufacturerSettings -> {
                                launchSettingsIntent(
                                    settingsLauncher,
                                    AndroidDeviceCompatibility.manufacturerSettingsIntent(
                                        this@MainActivity
                                    ),
                                    appDetailsIntent()
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionItems = buildPermissionItems()
    }

    private fun buildPermissionItems(): List<PermissionItem> {
        val runtimePermissions = runtimePermissions()
        val hasRuntimePermissions = runtimePermissions.all { permission ->
            hasPermission(permission)
        }
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val hasCallerIdRole = hasCallScreeningRole()
        val hasBatteryExemption = AndroidDeviceCompatibility.isIgnoringBatteryOptimizations(this)
        val deviceProfile = AndroidDeviceCompatibility.profile()
        val runtimeAction = if (hasRuntimePermissions) null else PermissionAction.RequestRuntimePermissions
        val runtimeActionLabel = if (hasRuntimePermissions) null else "Grant app permissions"

        val coreItems = listOf(
            runtimePermissionItem(
                title = "Microphone",
                description = "Used only after per-call consent.",
                isReady = hasPermission(Manifest.permission.RECORD_AUDIO),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            ),
            runtimePermissionItem(
                title = "Phone state",
                description = "Detects incoming calls and stops tracking when a call ends.",
                isReady = hasPhonePermissions(),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            ),
            runtimePermissionItem(
                title = "Contacts",
                description = "Allows Android to pass saved-contact calls to screening.",
                isReady = hasPermission(Manifest.permission.READ_CONTACTS),
                action = runtimeAction,
                actionLabel = runtimeActionLabel
            )
        )
        val notificationItems = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                runtimePermissionItem(
                    title = "Notifications",
                    description = "Shows Android's required active-call service notice.",
                    isReady = hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                    action = runtimeAction,
                    actionLabel = runtimeActionLabel
                )
            )
        } else {
            emptyList()
        }
        val specialItems = listOf(
            PermissionItem(
                title = "Display over apps",
                description = "Shows the call popup above the dialer.",
                state = permissionState(hasOverlayPermission),
                action = if (hasOverlayPermission) null else PermissionAction.OpenOverlaySettings,
                actionLabel = if (hasOverlayPermission) null else "Open overlay settings"
            ),
            PermissionItem(
                title = "Caller ID role",
                description = "Lets GuardVoice detect incoming callers.",
                state = permissionState(hasCallerIdRole),
                action = if (hasCallerIdRole) null else PermissionAction.OpenCallerIdSettings,
                actionLabel = if (hasCallerIdRole) null else "Set as Caller ID app"
            ),
            PermissionItem(
                title = "Battery background access",
                description = "Keeps call monitoring alive on Android versions and ROMs with strict battery control.",
                state = permissionState(hasBatteryExemption),
                action = if (hasBatteryExemption) null else PermissionAction.OpenBatterySettings,
                actionLabel = if (hasBatteryExemption) null else "Allow unrestricted battery"
            ),
            PermissionItem(
                title = deviceProfile.setupTitle,
                description = deviceProfile.setupDescription,
                state = PermissionState.Waiting,
                action = PermissionAction.OpenManufacturerSettings,
                actionLabel = deviceProfile.actionLabel
            )
        )

        return coreItems + notificationItems + specialItems
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
        hasPermission(Manifest.permission.READ_PHONE_STATE)

    private fun hasCallScreeningRole(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

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

    private fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun appDetailsIntent(): Intent =
        AndroidDeviceCompatibility.appDetailsIntent(this)

    private fun launchSettingsIntent(
        launcher: ActivityResultLauncher<Intent>,
        intent: Intent,
        fallbackIntent: Intent
    ) {
        try {
            launcher.launch(intent)
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "Settings screen was unavailable; opening fallback.", exception)
            launchFallbackSettings(launcher, fallbackIntent)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Settings screen launch failed; opening fallback.", exception)
            launchFallbackSettings(launcher, fallbackIntent)
        }
    }

    private fun launchFallbackSettings(
        launcher: ActivityResultLauncher<Intent>,
        fallbackIntent: Intent
    ) {
        try {
            launcher.launch(fallbackIntent)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Fallback settings screen launch failed.", exception)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (settingsException: RuntimeException) {
                Log.e(TAG, "Android settings could not be opened.", settingsException)
            }
        }
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}

private fun permissionState(isReady: Boolean): PermissionState =
    if (isReady) PermissionState.Ready else PermissionState.NeedsAction

private fun runtimePermissions(): Array<String> =
    buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
