package com.guardvoice.account

import com.guardvoice.ui.model.PlanTier

data class AccountProfile(
    val fullName: String,
    val email: String,
    val phoneNumber: String,
    val planTier: PlanTier
)

data class AccountValidation(
    val fullNameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null
) {
    val isValid: Boolean =
        fullNameError == null && emailError == null && passwordError == null
}
