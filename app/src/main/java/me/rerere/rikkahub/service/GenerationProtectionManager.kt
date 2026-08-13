package me.rerere.rikkahub.service

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging

private const val PROTECTION_TRACE_TAG = "GenerationProtection"

/** The kind is diagnostic only; chat and image work share one foreground-service lifetime. */
enum class GenerationKind {
    CHAT,
    IMAGE,
}

/**
 * The generation must not proceed after Android has refused or lost foreground protection.
 *
 * This is deliberately distinct from provider/network failures so callers can surface a retryable
 * local error without pretending the model completed normally.
 */
class GenerationProtectionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** An opaque, idempotent ownership token for a protected generation. */
interface GenerationLease {
    val kind: GenerationKind
    val runToken: Long

    /** Releases the lease. Calling this more than once is harmless. */
    suspend fun close()
}

/**
 * Coordinates a single Android foreground service across chat streams and image requests.
 *
 * The first lease initiates the foreground-service start before this function waits for anything.
 * It then waits for an explicit ACTIVE signal rather than treating an arbitrary delay as failure.
 */
class GenerationProtectionManager private constructor(
    private val serviceController: GenerationForegroundServiceController,
) {
    constructor(context: Context) : this(
        serviceController = AndroidGenerationForegroundServiceController.also {
            GenerationForegroundService.initialize(context)
        },
    )

    internal companion object {
        fun forTesting(controller: GenerationForegroundServiceController) =
            GenerationProtectionManager(controller)
    }

    private data class ActiveRun(
        val runToken: Long,
        val leases: MutableMap<Long, LeaseMetadata> = linkedMapOf(),
        var serviceStartAccepted: Boolean = false,
        var notificationConversationId: String? = null,
        var notificationConversationRevision: Long = 0L,
    ) {
        fun latestChatConversationId(): String? = leases.entries
            .lastOrNull { (_, metadata) ->
                metadata.kind == GenerationKind.CHAT && metadata.conversationId != null
            }
            ?.value
            ?.conversationId
    }

    private data class LeaseMetadata(
        val kind: GenerationKind,
        val conversationId: String?,
    )

    private data class NotificationTargetUpdate(
        val runToken: Long,
        val conversationRevision: Long,
        val conversationId: String?,
    )

    private val lock = Any()
    private val mutableHasActiveLeases = MutableStateFlow(false)
    /** True while any chat or image request owns foreground-generation protection. */
    val hasActiveLeases: StateFlow<Boolean> = mutableHasActiveLeases.asStateFlow()
    private var nextRunToken = 0L
    private var nextLeaseId = 0L
    private var activeRun: ActiveRun? = null

    /**
     * Starts foreground-service protection synchronously and returns a provisional lease.
     *
     * This deliberately does not suspend. Call it on the direct user-action path (for example
     * before the first suspension in `launch(UNDISPATCHED)`) and then call [awaitActive] before
     * beginning a network/model request.
     */
    fun begin(
        kind: GenerationKind,
        conversationId: String? = null,
    ): GenerationLease {
        val (lease, startRequest, notificationUpdate) = synchronized(lock) {
            val existingRun = activeRun
            val existingTerminal = existingRun?.let { terminalStateFor(it.runToken) }
            if (
                existingRun != null &&
                (existingTerminal is GenerationForegroundServiceState.Failed ||
                    existingTerminal is GenerationForegroundServiceState.Stopped)
            ) {
                // The old service is already terminal. Its leases will close in their finally
                // blocks, but a new user attempt must receive a fresh run token immediately.
                activeRun = null
                mutableHasActiveLeases.value = false
            }
            val run = activeRun ?: ActiveRun(++nextRunToken).also { activeRun = it }
            val lease = Lease(
                owner = this,
                id = ++nextLeaseId,
                kind = kind,
                runToken = run.runToken,
            )
            val previousConversationId = run.notificationConversationId
            run.leases[lease.id] = LeaseMetadata(
                kind = kind,
                conversationId = conversationId?.takeIf { kind == GenerationKind.CHAT && it.isNotBlank() },
            )
            run.notificationConversationId = run.latestChatConversationId()
            if (previousConversationId != run.notificationConversationId) {
                run.notificationConversationRevision++
            }
            mutableHasActiveLeases.value = true
            val request = if (run.leases.size == 1) {
                GenerationServiceRequest(
                    runToken = run.runToken,
                    kind = kind,
                    conversationId = run.notificationConversationId,
                    conversationRevision = run.notificationConversationRevision,
                )
            } else {
                null
            }
            val update = if (
                request == null &&
                run.serviceStartAccepted &&
                previousConversationId != run.notificationConversationId
            ) {
                run.notificationTargetUpdate()
            } else {
                null
            }
            Triple(lease, request, update)
        }

        if (startRequest != null) {
            // ContextCompat.startForegroundService is synchronous. This runs before the first
            // suspension in the normal UNDIDSPATCHED user-action path.
            val startResult = serviceController.start(startRequest)
            Logging.log(
                PROTECTION_TRACE_TAG,
                "service_start run=${lease.runToken} result=" +
                    if (startResult.isSuccess) "accepted" else "rejected",
            )
            val (postStartUpdate, stopCancelledRun) = synchronized(lock) {
                val currentRun = activeRun?.takeIf { it.runToken == lease.runToken }
                if (currentRun == null) {
                    // The provisional lease was released while startForegroundService() was in
                    // progress. An accepted start still needs a token-scoped stop so its queued
                    // ACTION_START cannot leave an ownerless foreground service behind.
                    null to startResult.isSuccess
                } else {
                    currentRun.serviceStartAccepted = startResult.isSuccess
                    val update = if (
                        startResult.isSuccess &&
                        currentRun.notificationConversationRevision > startRequest.conversationRevision
                    ) {
                        currentRun.notificationTargetUpdate()
                    } else {
                        null
                    }
                    update to false
                }
            }
            startResult.exceptionOrNull()?.let { error ->
                serviceController.reportFailure(
                    lease.runToken,
                    "Unable to start background generation protection",
                    error,
                )
            }
            postStartUpdate?.let { update -> serviceController.dispatchNotificationTargetUpdate(update) }
            if (stopCancelledRun) {
                serviceController.stop(lease.runToken)
            }
        } else if (notificationUpdate != null) {
            serviceController.dispatchNotificationTargetUpdate(notificationUpdate)
        }
        Logging.log(
            PROTECTION_TRACE_TAG,
            "lease_begin run=${lease.runToken} lease=${lease.id} kind=${lease.kind} " +
                "starts_service=${startRequest != null}",
        )
        return lease
    }

    /**
     * Waits for an explicit ACTIVE signal for [lease]'s run. A rejected start, service failure,
     * unexpected stop, or caller cancellation closes the provisional lease before propagating.
     */
    suspend fun awaitActive(lease: GenerationLease): GenerationLease {
        val ownedLease = lease as? Lease
            ?: throw IllegalArgumentException("Lease was not created by GenerationProtectionManager")

        return try {
            awaitActivation(ownedLease.runToken)
            Logging.log(
                PROTECTION_TRACE_TAG,
                "lease_active run=${ownedLease.runToken} lease=${ownedLease.id} kind=${ownedLease.kind}",
            )
            lease
        } catch (error: Throwable) {
            Logging.log(
                PROTECTION_TRACE_TAG,
                "lease_activation_failed run=${ownedLease.runToken} lease=${ownedLease.id} " +
                    "error=${error.javaClass.simpleName}",
            )
            withContext(NonCancellable) { ownedLease.close() }
            throw error
        }
    }

    /**
     * Convenience for callers that do not need to split synchronous startup from activation.
     * Prefer [begin] plus [awaitActive] for the direct user-send path.
     */
    suspend fun acquire(
        kind: GenerationKind,
        conversationId: String? = null,
    ): GenerationLease = awaitActive(begin(kind, conversationId))

    /**
     * Runs [block] while a lease is held and cancels it if Android destroys the protection service.
     * The caller receives [GenerationProtectionException], never a false successful completion.
     */
    suspend fun <T> withProtection(
        kind: GenerationKind,
        conversationId: String? = null,
        block: suspend () -> T,
    ): T {
        val lease = begin(kind, conversationId)
        return withActiveLease(lease, block)
    }

    /**
     * Waits for [lease] to become active, then watches for Android destroying the service while
     * [block] is running. This is the companion to [begin] for flows which must persist local work
     * before their first billable request.
     */
    suspend fun <T> withActiveLease(
        lease: GenerationLease,
        block: suspend () -> T,
    ): T {
        val ownedLease = lease as? Lease
            ?: throw IllegalArgumentException("Lease was not created by GenerationProtectionManager")
        awaitActive(ownedLease)
        return try {
            coroutineScope {
                val lossMonitor = launch {
                    throw awaitUnexpectedServiceLoss(ownedLease.runToken)
                }
                try {
                    block().also {
                        // The loss monitor and a normally finishing block can race on the same
                        // dispatcher. Check the current token state before accepting a result so
                        // a service destruction which won that race cannot be reported as a
                        // completed generation.
                        ensureServiceStillActive(ownedLease.runToken)
                    }
                } finally {
                    // coroutineScope waits for children itself. Cancelling is enough here and
                    // avoids masking the typed protection failure with a cancellation while its
                    // sibling block is unwinding.
                    lossMonitor.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) { ownedLease.close() }
        }
    }

    /** Best-effort snapshot for lifecycle guards such as deferred database maintenance. */
    fun hasActiveLeases(): Boolean = hasActiveLeases.value

    /** Updates the single foreground notification only while [runToken] still owns protection. */
    fun updateProgress(
        runToken: Long,
        conversationId: String,
        title: String,
        status: String,
        content: String,
        chipText: String,
    ) {
        val conversationRevision = synchronized(lock) {
            activeRun?.let { run ->
                run.notificationConversationRevision.takeIf {
                    run.runToken == runToken && run.serviceStartAccepted &&
                        run.notificationConversationId == conversationId
                }
            }
        }
        if (conversationRevision != null) {
            serviceController.updateProgress(
                runToken = runToken,
                conversationRevision = conversationRevision,
                conversationId = conversationId,
                title = title,
                status = status,
                content = content,
                chipText = chipText,
            )
        }
    }

    /** True only when Android explicitly failed or stopped this foreground-service run. */
    internal fun isProtectionLost(runToken: Long): Boolean = terminalStateFor(runToken) != null

    private suspend fun awaitActivation(runToken: Long) {
        val knownTerminal = terminalStateFor(runToken)
        if (knownTerminal is GenerationForegroundServiceState.Failed) throw protectionException(knownTerminal)
        if (knownTerminal is GenerationForegroundServiceState.Stopped) {
            throw GenerationProtectionException("Background generation protection stopped before it became active")
        }
        when (
            val state = combine(serviceController.state, serviceController.terminalStates) { current, terminals ->
                current to terminals[runToken]
            }.first { (current, terminal) ->
                terminal != null || when (current) {
                    is GenerationForegroundServiceState.Active -> current.runToken == runToken
                    is GenerationForegroundServiceState.Failed -> current.runToken == runToken
                    is GenerationForegroundServiceState.Stopped -> current.runToken == runToken
                    else -> false
                }
            }.let { (current, terminal) -> terminal ?: current }
        ) {
            is GenerationForegroundServiceState.Active -> Unit
            is GenerationForegroundServiceState.Failed -> throw protectionException(state)
            is GenerationForegroundServiceState.Stopped -> throw GenerationProtectionException(
                "Background generation protection stopped before it became active",
            )
            else -> error("Unexpected generation foreground-service state: $state")
        }
    }

    private suspend fun awaitUnexpectedServiceLoss(runToken: Long): GenerationProtectionException {
        val knownTerminal = terminalStateFor(runToken)
        if (knownTerminal is GenerationForegroundServiceState.Failed) return protectionException(knownTerminal)
        if (knownTerminal is GenerationForegroundServiceState.Stopped) {
            return GenerationProtectionException("Background generation protection stopped unexpectedly")
        }
        return when (
            val state = combine(serviceController.state, serviceController.terminalStates) { current, terminals ->
                current to terminals[runToken]
            }.first { (current, terminal) ->
                terminal != null || current is GenerationForegroundServiceState.Failed && current.runToken == runToken ||
                    current is GenerationForegroundServiceState.Stopped && current.runToken == runToken
            }.let { (current, terminal) -> terminal ?: current }
        ) {
            is GenerationForegroundServiceState.Failed -> protectionException(state)
            is GenerationForegroundServiceState.Stopped -> GenerationProtectionException(
                "Background generation protection stopped unexpectedly",
            )
            else -> error("Unexpected generation foreground-service state: $state")
        }
    }

    private fun ensureServiceStillActive(runToken: Long) {
        val terminal = terminalStateFor(runToken)
        when (terminal) {
            is GenerationForegroundServiceState.Failed -> throw protectionException(terminal)
            is GenerationForegroundServiceState.Stopped -> throw GenerationProtectionException(
                "Background generation protection stopped unexpectedly",
            )

            else -> Unit
        }
        when (val state = serviceController.state.value) {
            is GenerationForegroundServiceState.Active -> {
                if (state.runToken == runToken) return
            }

            is GenerationForegroundServiceState.Failed -> {
                if (state.runToken == runToken) throw protectionException(state)
            }

            is GenerationForegroundServiceState.Stopped -> {
                if (state.runToken == runToken) {
                    throw GenerationProtectionException(
                        "Background generation protection stopped unexpectedly",
                    )
                }
            }

            else -> Unit
        }
        throw GenerationProtectionException(
            "Background generation protection is no longer active",
        )
    }

    private fun terminalStateFor(runToken: Long): GenerationForegroundServiceState? {
        val retained = serviceController.terminalStates.value[runToken]
        if (
            retained is GenerationForegroundServiceState.Failed ||
            retained is GenerationForegroundServiceState.Stopped
        ) {
            return retained
        }
        return when (val current = serviceController.state.value) {
            is GenerationForegroundServiceState.Failed -> current.takeIf { it.runToken == runToken }
            is GenerationForegroundServiceState.Stopped -> current.takeIf { it.runToken == runToken }
            else -> null
        }
    }

    /**
     * Starts service protection synchronously, waits for ACTIVE, and watches the run while [block]
     * executes. It is the convenient form of `begin -> persist -> awaitActive -> model request`.
     */
    suspend fun <T> withActiveProtection(
        lease: GenerationLease,
        block: suspend () -> T,
    ): T = withActiveLease(lease, block)

    private fun protectionException(state: GenerationForegroundServiceState.Failed) =
        GenerationProtectionException(state.message, state.cause)

    private fun release(lease: Lease) {
        val (stopRunToken, notificationUpdate) = synchronized(lock) {
            val run = activeRun
            if (run == null || run.runToken != lease.runToken || run.leases.remove(lease.id) == null) {
                return@synchronized null to null
            }
            val previousConversationId = run.notificationConversationId
            run.notificationConversationId = run.latestChatConversationId()
            if (previousConversationId != run.notificationConversationId) {
                run.notificationConversationRevision++
            }
            if (run.leases.isNotEmpty()) {
                val update = if (
                    run.serviceStartAccepted &&
                    previousConversationId != run.notificationConversationId
                ) {
                    run.notificationTargetUpdate()
                } else {
                    null
                }
                return@synchronized null to update
            }

            activeRun = null
            mutableHasActiveLeases.value = false
            run.runToken.takeIf { run.serviceStartAccepted } to null
        }
        notificationUpdate?.let { update -> serviceController.dispatchNotificationTargetUpdate(update) }
        // The controller state can already contain a late callback from an older run. The accepted
        // start result is the ownership source of truth; always issue the token-scoped stop for it.
        stopRunToken?.let(serviceController::stop)
    }

    private fun ActiveRun.notificationTargetUpdate() = NotificationTargetUpdate(
        runToken = runToken,
        conversationRevision = notificationConversationRevision,
        conversationId = notificationConversationId,
    )

    private fun GenerationForegroundServiceController.dispatchNotificationTargetUpdate(
        update: NotificationTargetUpdate,
    ) {
        updateConversation(
            runToken = update.runToken,
            conversationRevision = update.conversationRevision,
            conversationId = update.conversationId,
        )
    }

    private class Lease(
        private val owner: GenerationProtectionManager,
        val id: Long,
        override val kind: GenerationKind,
        override val runToken: Long,
    ) : GenerationLease {
        private val closed = AtomicBoolean(false)

        override suspend fun close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(this)
                Logging.log(
                    PROTECTION_TRACE_TAG,
                    "lease_release run=$runToken lease=$id kind=$kind",
                )
            }
        }
    }
}
