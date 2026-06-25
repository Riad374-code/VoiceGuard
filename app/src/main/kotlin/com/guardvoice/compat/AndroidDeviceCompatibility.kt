package com.guardvoice.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object AndroidDeviceCompatibility {
    fun profile(): DeviceCompatibilityProfile {
        val family = deviceFamilyFor(Build.MANUFACTURER)
        return when (family) {
            DeviceFamily.Xiaomi -> DeviceCompatibilityProfile(
                manufacturerLabel = "Redmi / Xiaomi / POCO",
                setupTitle = "Redmi compatibility",
                setupDescription = "Enable autostart, unrestricted battery, and floating windows if the call popup is delayed or blocked.",
                actionLabel = "Open Redmi settings",
                family = family
            )
            DeviceFamily.HonorHuawei -> DeviceCompatibilityProfile(
                manufacturerLabel = "Honor / Huawei",
                setupTitle = "Honor compatibility",
                setupDescription = "Allow manual app launch, background running, and display over other apps for reliable call detection.",
                actionLabel = "Open Honor settings",
                family = family
            )
            DeviceFamily.OppoRealmeOnePlus -> DeviceCompatibilityProfile(
                manufacturerLabel = "OPPO / Realme / OnePlus",
                setupTitle = "OPPO compatibility",
                setupDescription = "Allow auto launch, background activity, and floating windows so incoming-call popup starts.",
                actionLabel = "Open device settings",
                family = family
            )
            DeviceFamily.Vivo -> DeviceCompatibilityProfile(
                manufacturerLabel = "Vivo / iQOO",
                setupTitle = "Vivo compatibility",
                setupDescription = "Allow background startup and floating-window access for GuardVoice.",
                actionLabel = "Open Vivo settings",
                family = family
            )
            DeviceFamily.Samsung -> DeviceCompatibilityProfile(
                manufacturerLabel = "Samsung",
                setupTitle = "Samsung battery mode",
                setupDescription = "Keep GuardVoice unrestricted so Android does not pause call monitoring in the background.",
                actionLabel = "Open app settings",
                family = family
            )
            DeviceFamily.Generic -> DeviceCompatibilityProfile(
                manufacturerLabel = "Android",
                setupTitle = "Device compatibility",
                setupDescription = "Use app settings to allow background running if your phone blocks the call popup.",
                actionLabel = "Open app settings",
                family = family
            )
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun manufacturerSettingsIntent(context: Context): Intent {
        val packageManager = context.packageManager
        return manufacturerIntents(profile().family)
            .firstOrNull { intent -> intent.resolveActivity(packageManager) != null }
            ?: appDetailsIntent(context)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    private fun manufacturerIntents(family: DeviceFamily): List<Intent> =
        when (family) {
            DeviceFamily.Xiaomi -> listOf(
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            )
            DeviceFamily.HonorHuawei -> listOf(
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                componentIntent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            DeviceFamily.OppoRealmeOnePlus -> listOf(
                componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ),
                componentIntent(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            )
            DeviceFamily.Vivo -> listOf(
                componentIntent(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                componentIntent(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            DeviceFamily.Samsung,
            DeviceFamily.Generic -> emptyList()
        }

    private fun componentIntent(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))
}

data class DeviceCompatibilityProfile(
    val manufacturerLabel: String,
    val setupTitle: String,
    val setupDescription: String,
    val actionLabel: String,
    val family: DeviceFamily
)

enum class DeviceFamily {
    Xiaomi,
    HonorHuawei,
    OppoRealmeOnePlus,
    Vivo,
    Samsung,
    Generic
}

internal fun deviceFamilyFor(manufacturer: String): DeviceFamily {
    val normalizedManufacturer = manufacturer.trim().lowercase()
    return when {
        normalizedManufacturer in setOf("xiaomi", "redmi", "poco") -> DeviceFamily.Xiaomi
        normalizedManufacturer in setOf("honor", "huawei") -> DeviceFamily.HonorHuawei
        normalizedManufacturer in setOf("oppo", "realme", "oneplus") -> {
            DeviceFamily.OppoRealmeOnePlus
        }
        normalizedManufacturer in setOf("vivo", "iqoo") -> DeviceFamily.Vivo
        normalizedManufacturer == "samsung" -> DeviceFamily.Samsung
        else -> DeviceFamily.Generic
    }
}
