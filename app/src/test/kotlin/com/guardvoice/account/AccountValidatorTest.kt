package com.guardvoice.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountValidatorTest {
    @Test
    fun `accepts a valid registration`() {
        val result = AccountValidator.validateRegistration(
            fullName = "Alex Morgan",
            email = "alex@example.com",
            password = "password"
        )

        assertTrue(result.isValid)
        assertNull(result.fullNameError)
        assertNull(result.emailError)
        assertNull(result.passwordError)
    }

    @Test
    fun `rejects malformed registration fields`() {
        val result = AccountValidator.validateRegistration(
            fullName = "A",
            email = "not-an-email",
            password = "short"
        )

        assertFalse(result.isValid)
        assertTrue(result.fullNameError != null)
        assertTrue(result.emailError != null)
        assertTrue(result.passwordError != null)
    }
}
