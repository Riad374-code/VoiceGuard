package com.guardvoice.ui.model

import androidx.compose.ui.graphics.Color
import com.guardvoice.ui.theme.GuardColors

enum class AppDestination(val label: String) {
    Setup("Setup"),
    Home("Home"),
    Overlay("Call popup"),
    Summary("Result")
}

enum class PermissionState {
    Ready,
    NeedsAction,
    Waiting
}

enum class RiskLevel(
    val label: String,
    val color: Color,
    val background: Color
) {
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

data class PermissionItem(
    val title: String,
    val description: String,
    val state: PermissionState
)

data class CallInsight(
    val caller: String,
    val time: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val reason: String
)

data class LiveAnalysis(
    val caller: String,
    val elapsed: String,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val transcript: String,
    val reasons: List<String>
)

val setupPermissions = listOf(
    PermissionItem(
        title = "Microphone",
        description = "Used only after per-call consent.",
        state = PermissionState.Ready
    ),
    PermissionItem(
        title = "Contacts",
        description = "Skips saved callers automatically.",
        state = PermissionState.Ready
    ),
    PermissionItem(
        title = "Phone state",
        description = "Detects incoming calls from unknown numbers.",
        state = PermissionState.NeedsAction
    ),
    PermissionItem(
        title = "Display over apps",
        description = "Shows the call popup above the dialer.",
        state = PermissionState.Waiting
    ),
    PermissionItem(
        title = "Caller ID role",
        description = "Lets Android route unknown-call events here.",
        state = PermissionState.NeedsAction
    )
)

val recentCalls = listOf(
    CallInsight(
        caller = "+1 (312) 847-1928",
        time = "Today, 16:42",
        riskLevel = RiskLevel.Scam,
        riskScore = 83,
        reason = "Gift-card payment and urgency language"
    ),
    CallInsight(
        caller = "+994 50 214 78 61",
        time = "Today, 12:08",
        riskLevel = RiskLevel.Suspicious,
        riskScore = 47,
        reason = "Asked to verify bank details"
    ),
    CallInsight(
        caller = "+44 20 7183 4862",
        time = "Yesterday, 19:31",
        riskLevel = RiskLevel.Safe,
        riskScore = 12,
        reason = "No coercion or payment request detected"
    )
)

val demoAnalysis = LiveAnalysis(
    caller = "+994 50 214 78 61",
    elapsed = "00:46",
    riskLevel = RiskLevel.Suspicious,
    riskScore = 47,
    transcript = "Caller says your account is restricted and asks you to confirm a card number before the branch closes.",
    reasons = listOf(
        "Bank impersonation pattern",
        "Personal info request",
        "Time pressure"
    )
)
