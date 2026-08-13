package me.rerere.rikkahub.ui.pages.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.ui.pages.auth.components.AuthScaffold
import me.rerere.rikkahub.ui.pages.auth.components.EmailField
import me.rerere.rikkahub.ui.pages.auth.components.FormError
import me.rerere.rikkahub.ui.pages.auth.components.PasswordField
import me.rerere.rikkahub.ui.pages.auth.components.SubmitProgress
import org.koin.androidx.compose.koinViewModel

@Composable
fun RegisterPage(vm: AuthVM = koinViewModel()) {
    val navController = LocalNavController.current
    val state by vm.uiState.collectAsStateWithLifecycle()

    val submit = {
        vm.register { navigateToChatPage(navController) }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_register_headline),
        subtitle = stringResource(R.string.auth_register_subtitle),
    ) {
        EmailField(
            value = state.email,
            onValueChange = vm::setEmail,
            error = state.emailError,
            enabled = !state.submitting,
        )
        PasswordField(
            value = state.password,
            onValueChange = vm::setPassword,
            error = state.passwordError,
            enabled = !state.submitting,
            imeAction = ImeAction.Next,
        )

        val codeError = state.codeError

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = state.code,
                onValueChange = vm::setCode,
                modifier = Modifier.weight(1f),
                enabled = !state.submitting,
                label = { Text(stringResource(R.string.auth_email_code)) },
                singleLine = true,
                isError = codeError != null,
                supportingText = if (codeError != null) {
                    { Text(codeError) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )
            TextButton(
                onClick = vm::sendVerifyCode,
                modifier = Modifier.padding(top = 8.dp),
                enabled = state.canSendCode,
            ) {
                Text(
                    when {
                        state.resendSecondsRemaining > 0 -> stringResource(
                            R.string.auth_resend_in,
                            state.resendSecondsRemaining,
                        )

                        state.codeSent -> stringResource(R.string.auth_resend_code)
                        else -> stringResource(R.string.auth_send_code)
                    }
                )
            }
        }

        FormError(state.formError)

        Button(
            onClick = submit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = state.canSubmitRegister,
        ) {
            if (state.submitting) {
                SubmitProgress()
            } else {
                Text(
                    text = stringResource(R.string.auth_register_submit),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.auth_register_have_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    vm.clearErrors()
                    navController.popBackStack()
                },
                enabled = !state.submitting,
            ) {
                Text(stringResource(R.string.auth_register_login_link))
            }
        }
    }
}
