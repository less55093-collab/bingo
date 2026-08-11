package me.rerere.rikkahub.ui.pages.account

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.rerere.rikkahub.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.api.gateway.GatewayException
import me.rerere.rikkahub.data.api.gateway.GatewayReasons
import me.rerere.rikkahub.data.model.gateway.RedeemHistoryItem
import me.rerere.rikkahub.data.model.gateway.RedeemResult
import me.rerere.rikkahub.data.repository.AccountRepository
import java.io.IOException

data class RedeemUiState(
    val code: String = "",
    val codeError: String? = null,
    val submitting: Boolean = false,
    val history: List<RedeemHistoryItem> = emptyList(),
    val historyLoading: Boolean = false,
) {
    val canSubmit: Boolean get() = !submitting && code.isNotBlank()
}

class RedeemVM(
    private val context: Application,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RedeemUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    /** Preserve case and punctuation because Sub2API compares redeem codes exactly. */
    fun setCode(value: String) = _uiState.update {
        it.copy(
            code = AccountRepository.normalizeRedeemCode(value),
            codeError = null,
        )
    }

    fun redeem(onSuccess: (RedeemResult) -> Unit) {
        val code = _uiState.value.code
        if (code.isBlank()) return
        _uiState.update { it.copy(submitting = true, codeError = null) }
        viewModelScope.launch {
            runCatching { accountRepository.redeem(code) }
                .onSuccess { result ->
                    _uiState.update { it.copy(submitting = false, code = "") }
                    onSuccess(result)
                    loadHistory()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(submitting = false, codeError = describe(error)) }
                }
        }
    }

    fun loadHistory() {
        if (_uiState.value.historyLoading) return
        _uiState.update { it.copy(historyLoading = true) }
        viewModelScope.launch {
            val items = runCatching { accountRepository.redeemHistory() }.getOrNull()
            _uiState.update {
                it.copy(historyLoading = false, history = items ?: it.history)
            }
        }
    }

    /**
     * Redeem failures annotate the field rather than raise a toast: a toast can be missed, and the
     * user's next action is always to re-check what they typed.
     */
    private fun describe(error: Throwable): String = when {
        error is GatewayException -> when (error.reason) {
            GatewayReasons.REDEEM_CODE_NOT_FOUND -> context.getString(R.string.redeem_error_not_found)
            GatewayReasons.REDEEM_CODE_USED -> context.getString(R.string.redeem_error_used)
            else -> error.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.redeem_error_unknown)
        }

        error is IOException -> context.getString(R.string.redeem_error_network)
        else -> context.getString(R.string.redeem_error_unknown)
    }
}

/** The gateway ships more code types than balance; a concurrency code bought by mistake still
 *  needs to render as something meaningful instead of a blank row. Null means the gateway sent a
 *  type we have no copy for, so the caller falls back to showing the raw value.
 *
 *  Both the bare and `admin_`-prefixed spellings are accepted: `POST /redeem` and
 *  `GET /redeem/history` return `"balance"` for a real code, while admin-issued rows have been
 *  observed as `"admin_balance"`. Matching only one spelling mislabels live rows. */
@StringRes
fun redeemTypeLabel(type: String): Int? = when (normalizeRedeemType(type)) {
    "balance" -> R.string.redeem_type_balance
    "concurrency" -> R.string.redeem_type_concurrency
    "subscription" -> R.string.redeem_type_subscription
    else -> null
}

/** Only balance codes are money; a concurrency code's value is a count, so it gets no ¥. */
fun formatRedeemValue(type: String, value: Double): String =
    if (normalizeRedeemType(type) == "balance") formatBalance(value) else formatBalanceAmount(value)

private fun normalizeRedeemType(type: String): String =
    type.trim().lowercase().removePrefix("admin_")
