package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        // Request bodies and headers can contain prompts, model output, API keys, and OAuth tokens.
        // Keep this diagnostic log to transport metadata only.
        val safeUrl = request.url.redact()

        val response: Response

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = safeUrl,
                    method = request.method,
                    error = e.javaClass.simpleName,
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = safeUrl,
                method = request.method,
                responseCode = response.code,
                durationMs = durationMs,
            )
        )

        return response
    }
}
