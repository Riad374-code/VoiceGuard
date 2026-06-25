package com.guardvoice.ui.model

import androidx.compose.ui.graphics.Color
import com.guardvoice.ui.theme.GuardColors

enum class AppDestination(val label: String) {
    Setup("Setup"),
    Dashboard("Dashboard"),
    Overlay("Call popup"),
    Billing("Billing"),
    Account("Account"),
    Settings("Settings"),
    Summary("Result")
}

enum class PermissionState {
    Ready,
    NeedsAction,
    Waiting
}

enum class PermissionAction {
    RequestRuntimePermissions,
    OpenOverlaySettings,
    OpenCallerIdSettings,
    OpenBatterySettings,
    OpenManufacturerSettings
}

enum class RiskLevel(
    val label: String,
    val color: Color,
    val background: Color
) {
    Pending(
        label = "Pending",
        color = GuardColors.InkMuted,
        background = GuardColors.SurfaceMuted
    ),
    Safe(
        label = "Safe",
        color = GuardColors.Forest,
        background = GuardColors.ForestSoft
    ),
    Suspicious(
        label = "Suspicious",
        color = GuardColors.Amber,
        background = GuardColors.AmberSoft
    ),
    Scam(
        label = "Scam",
        color = GuardColors.Rose,
        background = GuardColors.RoseSoft
    )
}

enum class PlanTier(val label: String) {
    Free("Free"),
    Plus("Plus"),
    Family("Family")
}

data class PermissionItem(
    val title: String,
    val description: String,
    val state: PermissionState,
    val action: PermissionAction? = null,
    val actionLabel: String? = null
)

data class CallInsight(
    val caller: String,
    val time: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val reason: String
)

data class PredictionSummary(
    val safeCount: Int,
    val suspiciousCount: Int,
    val scamCount: Int
) {
    val totalCount: Int = safeCount + suspiciousCount + scamCount
}

data class LiveAnalysis(
    val caller: String,
    val elapsed: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val transcript: String,
    val reasons: List<String>
)

data class BillingPlan(
    val tier: PlanTier,
    val price: String,
    val description: String,
    val features: List<String>,
    val isCurrent: Boolean
)

data class BillingUsage(
    val analyzedMinutes: Int,
    val includedMinutes: Int,
    val renewalLabel: String
)

data class SettingItem(
    val title: String,
    val description: String,
    val isEnabled: Boolean
)

val detectedCalls = emptyList<CallInsight>()

val predictionSummary = PredictionSummary(
    safeCount = 0,
    suspiciousCount = 0,
    scamCount = 0
)

val demoAnalysis = LiveAnalysis(
    caller = "Incoming caller",
    elapsed = "00:00",
    riskLevel = RiskLevel.Pending,
    riskScore = 0,
    transcript = "Transcript and verdict will appear here after the audio pipeline starts.",
    reasons = emptyList()
)

val billingPlans = listOf(
    BillingPlan(
        tier = PlanTier.Free,
        price = "No billing",
        description = "UI preview plan for local development.",
        features = listOf("Per-call consent popup", "Local setup checklist", "Manual preview mode"),
        isCurrent = true
    ),
    BillingPlan(
        tier = PlanTier.Plus,
        price = "Not set",
        description = "Planned tier for real-time AI call analysis.",
        features = listOf("Live scam scoring", "Call summaries", "Priority model routing"),
        isCurrent = false
    ),
    BillingPlan(
        tier = PlanTier.Family,
        price = "Not set",
        description = "Planned shared protection for multiple devices.",
        features = listOf("Shared alerts", "Family dashboard", "Centralized billing"),
        isCurrent = false
    )
)

val billingUsage = BillingUsage(
    analyzedMinutes = 0,
    includedMinutes = 0,
    renewalLabel = "Billing is not connected yet"
)

val defaultSettings = listOf(
    SettingItem(
        title = "Ask before every call",
        description = "Keep analysis opt-in for each incoming number.",
        isEnabled = true
    ),
    SettingItem(
        title = "Auto speaker after consent",
        description = "Route audio to speaker only after the user allows tracking.",
        isEnabled = true
    ),
    SettingItem(
        title = "Save transcripts locally",
        description = "Keep call summaries on device when storage is added.",
        isEnabled = false
    ),
    SettingItem(
        title = "Share scam reports",
        description = "Send confirmed scam patterns to a community database later.",
        isEnabled = false
    )
)
