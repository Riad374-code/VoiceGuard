package com.guardvoice.ui.model

import com.guardvoice.data.CallSession
import com.guardvoice.data.CallSessionStatus
import com.guardvoice.data.CallVerdict
import com.guardvoice.data.audioDurationLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun predictionSummaryFromSessions(sessions: List<CallSession>): PredictionSummary =
    PredictionSummary(
        safeCount = sessions.count { session -> session.verdict == CallVerdict.Safe },
        suspiciousCount = sessions.count { session -> session.verdict == CallVerdict.Suspicious },
        scamCount = sessions.count { session -> session.verdict == CallVerdict.Scam }
    )

fun callInsightsFromSessions(sessions: List<CallSession>): List<CallInsight> =
    sessions.map { session ->
        CallInsight(
            caller = session.phoneNumber.ifBlank { "Incoming caller" },
            time = formatSessionTime(session.updatedAtMillis),
            riskLevel = riskLevelForVerdict(session.verdict),
            riskScore = session.riskScore,
            reason = reasonForSession(session)
        )
    }

fun liveAnalysisFromSession(session: CallSession?): LiveAnalysis =
    if (session == null) {
        demoAnalysis
    } else {
        LiveAnalysis(
            caller = session.phoneNumber.ifBlank { "Incoming caller" },
            elapsed = audioDurationLabel(session.audioBytesStreamed),
            riskLevel = riskLevelForVerdict(session.verdict),
            riskScore = session.riskScore,
            transcript = session.transcriptPreview.ifBlank {
                "No transcript yet. Audio is only counted until AI is connected."
            },
            reasons = session.reasons
        )
    }

fun riskLevelForVerdict(verdict: CallVerdict): RiskLevel =
    when (verdict) {
        CallVerdict.Pending -> RiskLevel.Pending
        CallVerdict.Safe -> RiskLevel.Safe
        CallVerdict.Suspicious -> RiskLevel.Suspicious
        CallVerdict.Scam -> RiskLevel.Scam
    }

private fun reasonForSession(session: CallSession): String =
    when (session.status) {
        CallSessionStatus.Detected -> "Incoming call detected; waiting for consent."
        CallSessionStatus.Listening -> "Listening now. ${audioDurationLabel(session.audioBytesStreamed)} streamed."
        CallSessionStatus.Completed -> {
            "${audioDurationLabel(session.audioBytesStreamed)} streamed. AI verdict pending."
        }
        CallSessionStatus.Declined -> "Tracking was skipped for this call."
        CallSessionStatus.Failed -> session.summary.ifBlank { "Call tracking failed." }
    }

private fun formatSessionTime(updatedAtMillis: Long): String {
    if (updatedAtMillis <= 0L) {
        return "--:--"
    }
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAtMillis))
}
