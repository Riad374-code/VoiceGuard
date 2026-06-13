package com.guardvoice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.guardvoice.account.AccountProfile
import com.guardvoice.account.AccountValidation
import com.guardvoice.account.AccountValidator
import com.guardvoice.ui.components.AppSurface
import com.guardvoice.ui.components.PrimaryAction
import com.guardvoice.ui.components.SecondaryAction
import com.guardvoice.ui.components.SectionLabel
import com.guardvoice.ui.theme.GuardColors
import com.guardvoice.ui.theme.GuardSpace

private enum class AuthMode {
    Login,
    Register
}

@Composable
fun AccountScreen(
    profile: AccountProfile?,
    onAuthenticate: (fullName: String, email: String) -> Unit,
    onProfileUpdate: (AccountProfile) -> Unit,
    onLogout: () -> Unit,
    onOpenPlans: () -> Unit
) {
    if (profile == null) {
        AuthenticationPanel(onAuthenticate = onAuthenticate)
    } else {
        ProfilePanel(
            profile = profile,
            onProfileUpdate = onProfileUpdate,
            onLogout = onLogout,
            onOpenPlans = onOpenPlans
        )
    }
}

@Composable
private fun AuthenticationPanel(
    onAuthenticate: (fullName: String, email: String) -> Unit
) {
    var authModeName by rememberSaveable { mutableStateOf(AuthMode.Login.name) }
    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf(AccountValidation()) }
    val authMode = AuthMode.valueOf(authModeName)

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Account")
                Text(
                    text = if (authMode == AuthMode.Login) "Log in" else "Create your account",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "This is a local account prototype. No password is stored or sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                AuthModeSelector(
                    authMode = authMode,
                    onModeChange = {
                        authModeName = it.name
                        validation = AccountValidation()
                    }
                )
                if (authMode == AuthMode.Register) {
                    AccountField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = "Full name",
                        error = validation.fullNameError
                    )
                }
                AccountField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    error = validation.emailError,
                    keyboardType = KeyboardType.Email
                )
                AccountField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    error = validation.passwordError,
                    keyboardType = KeyboardType.Password,
                    isPassword = true
                )
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = if (authMode == AuthMode.Login) "Log in" else "Register",
                    onClick = {
                        val result = if (authMode == AuthMode.Login) {
                            AccountValidator.validateLogin(email, password)
                        } else {
                            AccountValidator.validateRegistration(fullName, email, password)
                        }
                        validation = result
                        if (result.isValid) {
                            val displayName = fullName.trim().ifBlank {
                                email.substringBefore("@").replaceFirstChar(Char::uppercase)
                            }
                            onAuthenticate(displayName, email.trim())
                            password = ""
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthModeSelector(
    authMode: AuthMode,
    onModeChange: (AuthMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GuardSpace.Small)
    ) {
        if (authMode == AuthMode.Login) {
            PrimaryAction(
                modifier = Modifier.weight(1f),
                text = "Login",
                onClick = { onModeChange(AuthMode.Login) }
            )
            SecondaryAction(
                modifier = Modifier.weight(1f),
                text = "Register",
                onClick = { onModeChange(AuthMode.Register) }
            )
        } else {
            SecondaryAction(
                modifier = Modifier.weight(1f),
                text = "Login",
                onClick = { onModeChange(AuthMode.Login) }
            )
            PrimaryAction(
                modifier = Modifier.weight(1f),
                text = "Register",
                onClick = { onModeChange(AuthMode.Register) }
            )
        }
    }
}

@Composable
private fun ProfilePanel(
    profile: AccountProfile,
    onProfileUpdate: (AccountProfile) -> Unit,
    onLogout: () -> Unit,
    onOpenPlans: () -> Unit
) {
    var fullName by rememberSaveable(profile.fullName) { mutableStateOf(profile.fullName) }
    var phoneNumber by rememberSaveable(profile.phoneNumber) {
        mutableStateOf(profile.phoneNumber)
    }

    Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Large)) {
        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Profile")
                Text(
                    text = profile.fullName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = profile.email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = GuardColors.InkMuted
                )
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Personal data")
                AccountField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = "Full name"
                )
                AccountField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = "Phone number",
                    keyboardType = KeyboardType.Phone
                )
                PrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "Save profile",
                    onClick = {
                        if (fullName.isNotBlank()) {
                            onProfileUpdate(
                                profile.copy(
                                    fullName = fullName.trim(),
                                    phoneNumber = phoneNumber.trim()
                                )
                            )
                        }
                    }
                )
            }
        }

        AppSurface {
            Column(verticalArrangement = Arrangement.spacedBy(GuardSpace.Medium)) {
                SectionLabel(text = "Plan")
                Text(
                    text = "${profile.planTier.label} plan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = GuardColors.Ink
                )
                Text(
                    text = "Manage plan options and future usage limits from billing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GuardColors.InkMuted
                )
                SecondaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    text = "View plans",
                    onClick = onOpenPlans
                )
            }
        }

        SecondaryAction(
            modifier = Modifier.fillMaxWidth(),
            text = "Log out",
            onClick = onLogout
        )
    }
}

@Composable
private fun AccountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = error?.let { message -> { Text(message) } },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }
    )
}
