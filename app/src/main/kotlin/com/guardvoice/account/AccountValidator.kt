package com.guardvoice.account

private const val MINIMUM_PASSWORD_LENGTH = 8

object AccountValidator {
    fun validateRegistration(
        fullName: String,
        email: String,
        password: String
    ): AccountValidation =
        AccountValidation(
            fullNameError = validateFullName(fullName),
            emailError = validateEmail(email),
            passwordError = validatePassword(password)
        )

    fun validateLogin(email: String, password: String): AccountValidation =
        AccountValidation(
            emailError = validateEmail(email),
            passwordError = validatePassword(password)
        )

    private fun validateFullName(fullName: String): String? =
        if (fullName.trim().length < 2) "Enter your full name." else null

    private fun validateEmail(email: String): String? {
        val normalizedEmail = email.trim()
        val hasValidShape =
            normalizedEmail.contains("@") &&
                normalizedEmail.substringAfter("@").contains(".") &&
                !normalizedEmail.contains(" ")
        return if (hasValidShape) null else "Enter a valid email address."
    }

    private fun validatePassword(password: String): String? =
        if (password.length < MINIMUM_PASSWORD_LENGTH) {
            "Use at least $MINIMUM_PASSWORD_LENGTH characters."
        } else {
            null
        }
}
