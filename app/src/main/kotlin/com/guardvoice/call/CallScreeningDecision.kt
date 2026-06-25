package com.guardvoice.call

internal fun shouldReportIncomingCall(
    isIncoming: Boolean,
    phoneNumber: String?
): Boolean =
    isIncoming &&
        !phoneNumber.isNullOrBlank()
