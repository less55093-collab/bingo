package me.rerere.rikkahub.service

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.provider.StreamInterruptedException

internal enum class BackgroundGenerationNoticeKind {
    FIRST_MESSAGE,
    INTERRUPTED,
}

internal fun shouldShowBackgroundGenerationNotice(
    appInForeground: Boolean,
    error: Throwable,
    protectionLost: Boolean,
    alreadyHandled: Boolean,
    alreadyPending: Boolean,
): Boolean = !appInForeground &&
    !protectionLost &&
    error is StreamInterruptedException &&
    !alreadyHandled &&
    !alreadyPending

internal fun shouldShowFirstBackgroundGenerationNotice(
    alreadyHandled: Boolean,
    alreadyPrompted: Boolean,
    alreadyPending: Boolean,
): Boolean = !alreadyHandled && !alreadyPrompted && !alreadyPending

/**
 * Queues one actionable hint for the first sent message, then falls back to an interruption-specific
 * hint for users who have not already handled it. OEM background policies cannot be queried or
 * changed by a third-party app, so the user remains in control of the setting and can permanently
 * dismiss this hint.
 */
class BackgroundGenerationInterruptionNotice(
    context: Context,
    lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : DefaultLifecycleObserver {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private var appInForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private val initialPendingKind = pendingKindFromPreferences()
    private val _pending = MutableStateFlow(initialPendingKind != null)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()
    private val _pendingKind = MutableStateFlow(initialPendingKind)
    internal val pendingKind: StateFlow<BackgroundGenerationNoticeKind?> = _pendingKind.asStateFlow()

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        appInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        appInForeground = false
    }

    /** Queues a proactive hint after the first user message is submitted on this installation. */
    @Synchronized
    fun recordFirstMessageIfEligible() {
        if (!shouldShowFirstBackgroundGenerationNotice(
                alreadyHandled = preferences.getBoolean(KEY_HANDLED, false),
                alreadyPrompted = preferences.getBoolean(KEY_FIRST_MESSAGE_PROMPTED, false),
                alreadyPending = isPending(),
            )
        ) {
            return
        }
        enqueue(BackgroundGenerationNoticeKind.FIRST_MESSAGE, firstMessagePrompted = true)
    }

    /** Does nothing for foreground failures, user stops, protection failures, pending, or repeat prompts. */
    @Synchronized
    fun recordIfEligible(error: Throwable, protectionLost: Boolean) {
        if (!shouldShowBackgroundGenerationNotice(
                appInForeground = appInForeground,
                error = error,
                protectionLost = protectionLost,
                alreadyHandled = preferences.getBoolean(KEY_HANDLED, false),
                alreadyPending = isPending(),
            )
        ) {
            return
        }
        enqueue(BackgroundGenerationNoticeKind.INTERRUPTED)
    }

    /** The user either opened the setting or explicitly declined it; never show this hint again. */
    @Synchronized
    fun acknowledge() {
        preferences.edit {
            putBoolean(KEY_HANDLED, true)
            remove(KEY_PENDING)
            remove(KEY_PENDING_KIND)
        }
        _pending.value = false
        _pendingKind.value = null
    }

    private fun enqueue(kind: BackgroundGenerationNoticeKind, firstMessagePrompted: Boolean = false) {
        preferences.edit {
            putBoolean(KEY_PENDING, true)
            putString(KEY_PENDING_KIND, kind.name)
            if (firstMessagePrompted) putBoolean(KEY_FIRST_MESSAGE_PROMPTED, true)
        }
        _pendingKind.value = kind
        _pending.value = true
    }

    private fun isPending(): Boolean = _pending.value || preferences.getBoolean(KEY_PENDING, false)

    private fun pendingKindFromPreferences(): BackgroundGenerationNoticeKind? {
        if (preferences.getBoolean(KEY_HANDLED, false) || !preferences.getBoolean(KEY_PENDING, false)) {
            return null
        }
        return preferences.getString(KEY_PENDING_KIND, null)
            ?.let { value -> BackgroundGenerationNoticeKind.entries.find { it.name == value } }
            ?: BackgroundGenerationNoticeKind.INTERRUPTED
    }

    private companion object {
        const val PREFERENCES_NAME = "background_generation_notice"
        const val KEY_PENDING = "pending"
        const val KEY_PENDING_KIND = "pending_kind"
        const val KEY_HANDLED = "handled"
        const val KEY_FIRST_MESSAGE_PROMPTED = "first_message_prompted"
    }
}
