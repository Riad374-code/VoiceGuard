package com.guardvoice.data

enum class CallSessionStatus {
    Detected,
    Listening,
    Completed,
    Declined,
    Failed
}

enum class CallVerdict {
    Pending,
    Safe,
    Suspicious,
    Scam
}

data class CallSession(
    val id: String,
    val phoneNumber: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val status: CallSessionStatus,
    val verdict: CallVerdict,
    val riskScore: Int,
    val audioBytesStreamed: Long,
    val audioChunksStreamed: Int,
    val transcriptPreview: String,
    val summary: String,
    val reasons: List<String>
)
