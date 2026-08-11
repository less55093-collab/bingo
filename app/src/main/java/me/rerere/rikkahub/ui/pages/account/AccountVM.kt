package me.rerere.rikkahub.ui.pages.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.model.gateway.UserProfile
import me.rerere.rikkahub.data.repository.AccountRepository
import me.rerere.rikkahub.data.repository.AuthState

/**
 * Backs the account card and the account page. The profile is read from the token store rather than
 * refetched per screen, so the balance shown is whatever the last successful call cached; an
 * explicit [refresh] is what goes to the network.
 */
class AccountVM(
    private val accountRepository: AccountRepository,
    tokenStore: AuthTokenStore,
) : ViewModel() {
    val profile = tokenStore.profileFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { accountRepository.refreshProfile() }
            _refreshing.value = false
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            accountRepository.logout()
            onDone()
        }
    }
}

/** One gateway balance unit is one CNY, so amounts render with a ¥ prefix. */
fun UserProfile.formattedBalance(): String = formatBalance(balance)

fun formatBalance(value: Double): String = "¥" + formatBalanceAmount(value)

/** The bare number, for places that supply their own unit or none. */
fun formatBalanceAmount(value: Double): String = when {
    value == 0.0 -> "0"
    value < 0.01 -> "<0.01"
    else -> String.format("%.2f", value).trimEnd('0').trimEnd('.')
}

/** Below this, chat starts failing mid-conversation, so the UI nudges toward topping up. */
const val LOW_BALANCE_THRESHOLD = 0.5

val AuthState.profileOrNull: UserProfile?
    get() = (this as? AuthState.Authenticated)?.profile
