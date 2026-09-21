package me.rerere.ai.provider

/** Keeps the requested model identity attached to provider failures through the UI boundary. */
class ModelRequestException(val modelId: String, cause: Throwable) :
    RuntimeException("$modelId: ${cause.message.orEmpty()}", cause)
