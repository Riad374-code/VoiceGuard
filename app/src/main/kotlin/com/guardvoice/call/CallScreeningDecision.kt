package com.guardvoice.call

internal fun shouldReportUnsavedIncomingCall(
    isIncoming: Boolean,
    phoneNumber: String?,
    isSavedContact: Boolean
): Boolean =
    isIncoming &&
        !phoneNumber.isNullOrBlank() &&
        !isSavedContact
