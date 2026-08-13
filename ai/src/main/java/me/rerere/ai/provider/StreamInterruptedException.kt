package me.rerere.ai.provider

import java.io.IOException

/**
 * The upstream SSE connection ended before the provider sent a protocol-level completion event.
 *
 * This is deliberately distinct from caller cancellation: consumers can preserve partial output
 * and present an interrupted-generation state without treating it as a completed response.
 */
class StreamInterruptedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
