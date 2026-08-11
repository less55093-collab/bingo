package me.rerere.rikkahub.ui.pages.auth

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.api.gateway.GatewayException
import me.rerere.rikkahub.data.api.gateway.GatewayReasons
import me.rerere.rikkahub.data.repository.AccountRepository
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Field-level errors are kept separate from [AuthUiState.formError] because an invalid code or a
 * wrong password should annotate the offending field, while a network failure has no field to
 * attach to and belongs above the submit button.
 */
data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val codeError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    val codeSent: Boolean = false,
    val resendSecondsRemaining: Int = 0,
) {
    val canSubmitLogin: Boolean
        get() = !submitting && email.isNotBlank() && password.isNotBlank()

    val canSendCode: Boolean
        get() = !submitting && resendSecondsRemaining == 0 && email.isNotBlank()

    val canSubmitRegister: Boolean
        get() = !submitting && email.isNotBlank() && password.isNotBlank() && code.isNotBlank()
}

class AuthVM(
    private val context: Application,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    private fun string(resId: Int): String = context.getString(resId)

    fun setEmail(value: String) = _uiState.update {
        it.copy(email = value.trim(), emailError = null, formError = null)
    }

    fun setPassword(value: String) = _uiState.update {
        it.copy(password = value, passwordError = null, formError = null)
    }

    fun setCode(value: String) = _uiState.update {
        it.copy(code = value.trim(), codeError = null, formError = null)
    }

    fun sendVerifyCode() {
        val email = _uiState.value.email
        if (!isEmailValid(email)) {
            _uiState.update {
                it.copy(
                    emailError = if (email.isBlank()) {
                        string(R.string.auth_error_email_empty)
                    } else {
                        string(R.string.auth_error_email_invalid)
                    }
                )
            }
            return
        }
        launchGuarded(
            block = { accountRepository.sendVerifyCode(email) },
            onSuccess = {
                _uiState.update { it.copy(codeSent = true) }
                startResendCountdown()
            },
        )
    }

    fun register(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (!validateCredentials(state)) return
        launchGuarded(
            block = { accountRepository.register(state.email, state.password, state.code) },
            onSuccess = { onSuccess() },
        )
    }

    fun login(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (!validateCredentials(state, requireCode = false)) return
        launchGuarded(
            block = { accountRepository.login(state.email, state.password) },
            onSuccess = { onSuccess() },
        )
    }

    /** Clears transient state so navigating between login and register does not carry errors over. */
    fun clearErrors() = _uiState.update {
        it.copy(emailError = null, passwordError = null, codeError = null, formError = null)
    }

    private fun validateCredentials(state: AuthUiState, requireCode: Boolean = true): Boolean {
        var valid = true
        if (!isEmailValid(state.email)) {
            _uiState.update {
                it.copy(
                    emailError = if (state.email.isBlank()) {
                        string(R.string.auth_error_email_empty)
                    } else {
                        string(R.string.auth_error_email_invalid)
                    }
                )
            }
            valid = false
        }
        if (state.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update {
                it.copy(
                    passwordError = if (state.password.isEmpty()) {
                        string(R.string.auth_error_password_empty)
                    } else {
                        string(R.string.auth_error_password_short)
                    }
                )
            }
            valid = false
        }
        if (requireCode && state.code.isBlank()) {
            _uiState.update { it.copy(codeError = string(R.string.auth_error_code_empty)) }
            valid = false
        }
        return valid
    }

    private fun launchGuarded(
        block: suspend () -> Unit,
        onSuccess: () -> Unit,
    ) {
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = runCatching { block() }
            _uiState.update { it.copy(submitting = false) }
            result
                .onSuccess { onSuccess() }
                .onFailure { error -> applyError(error) }
        }
    }

    /**
     * Routes a failure to the field it belongs to. An unverified email is the one case that is not
     * really an error: the gateway is telling us to collect a code, so it steers the user forward.
     */
    private fun applyError(error: Throwable) {
        when {
            error is GatewayException && error.reason == GatewayReasons.EMAIL_VERIFY_REQUIRED -> {
                _uiState.update { it.copy(codeError = string(R.string.auth_error_verify_required)) }
                sendVerifyCode()
            }

            // 验证码类错误挂在验证码输入框上, 其余网关错误作为整表单错误展示
            error is GatewayException && error.reason == GatewayReasons.INVALID_VERIFY_CODE ->
                _uiState.update { it.copy(codeError = string(R.string.auth_error_code_invalid)) }

            error is GatewayException -> _uiState.update { it.copy(formError = describe(error)) }

            error is UnknownHostException -> _uiState.update {
                it.copy(formError = string(R.string.auth_error_network_unreachable))
            }

            error is SocketTimeoutException -> _uiState.update {
                it.copy(formError = string(R.string.auth_error_network_timeout))
            }

            error is IOException -> _uiState.update {
                it.copy(formError = string(R.string.auth_error_network_io))
            }

            else -> _uiState.update {
                it.copy(
                    formError = context.getString(
                        R.string.auth_error_unknown_detail,
                        error.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: error::class.simpleName.orEmpty(),
                    )
                )
            }
        }
    }

    /**
     * 把网关 reason 映射成中文文案。未收录的 reason 会带上 code 和服务端原文, 方便用户反馈时定位,
     * 而不是只看到一句「错误」。
     */
    private fun describe(error: GatewayException): String = when (error.reason) {
        GatewayReasons.INVALID_CREDENTIALS -> string(R.string.auth_error_credentials)
        GatewayReasons.USER_EXISTS -> string(R.string.auth_error_email_exists)
        GatewayReasons.INVALID_VERIFY_CODE -> string(R.string.auth_error_code_invalid)
        else -> context.getString(
            R.string.auth_error_gateway_fallback,
            error.code,
            error.message?.takeIf { it.isNotBlank() } ?: error.reason,
        )
    }

    private fun startResendCountdown() {
        viewModelScope.launch {
            _uiState.update { it.copy(resendSecondsRemaining = RESEND_COOLDOWN_SECONDS) }
            while (_uiState.value.resendSecondsRemaining > 0) {
                kotlinx.coroutines.delay(1000)
                _uiState.update { it.copy(resendSecondsRemaining = it.resendSecondsRemaining - 1) }
            }
        }
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private const val RESEND_COOLDOWN_SECONDS = 60
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")

        fun isEmailValid(email: String): Boolean = EMAIL_REGEX.matches(email.trim())
    }
}
