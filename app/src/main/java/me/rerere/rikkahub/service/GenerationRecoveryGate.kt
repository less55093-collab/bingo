package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred

/**
 * Closes after startup has converted any reply stranded by a process death into terminal history.
 * Conversation initialization waits for it so a screen cannot first render stale in-progress data.
 */
class GenerationRecoveryGate {
    private val completed = CompletableDeferred<Unit>()

    suspend fun await() = completed.await()

    fun complete() {
        completed.complete(Unit)
    }
}
