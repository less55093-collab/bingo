package me.rerere.rikkahub.ui.pages.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.auth.components.AuthScaffold
import me.rerere.rikkahub.ui.pages.auth.components.EmailField
import me.rerere.rikkahub.ui.pages.auth.components.FormError
import me.rerere.rikkahub.ui.pages.auth.components.PasswordField
import me.rerere.rikkahub.ui.pages.auth.components.SubmitProgress
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun LoginPage(vm: AuthVM = koinViewModel()) {
    val navController = LocalNavController.current
    val state by vm.uiState.collectAsStateWithLifecycle()

    val submit = {
        vm.login { navigateToChatPage(navController) }
    }

    AuthScaffold(
        title = stringResource(R.string.auth_login_headline),
        subtitle = stringResource(R.string.auth_login_subtitle),
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
            imeAction = ImeAction.Done,
            onSubmit = { if (state.canSubmitLogin) submit() },
        )
        FormError(state.formError)

        Button(
            onClick = submit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = state.canSubmitLogin,
        ) {
            if (state.submitting) {
                SubmitProgress(modifier = Modifier.height(24.dp))
            } else {
                Text(
                    text = stringResource(R.string.auth_login_title),
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
                text = stringResource(R.string.auth_login_new_here),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    vm.clearErrors()
                    navController.navigate(Screen.Register)
                },
                enabled = !state.submitting,
            ) {
                Text(stringResource(R.string.auth_register_link))
            }
        }
    }
}
