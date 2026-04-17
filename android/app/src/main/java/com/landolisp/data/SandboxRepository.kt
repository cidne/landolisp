package com.landolisp.data

import com.landolisp.data.api.SandboxApi
import com.landolisp.data.api.SandboxClient
import com.landolisp.data.model.EvalRequest
import com.landolisp.data.model.EvalResponse
import com.landolisp.data.model.HealthResponse
import com.landolisp.data.model.QuickloadRequest
import com.landolisp.data.model.QuickloadResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response

/**
 * Thin wrapper around [SandboxApi] that owns the lazy-created session id and recovers
 * automatically when the server reports the session has expired (HTTP 404).
 *
 * Thread-safe: session creation is guarded by a [Mutex]. Callers may invoke any suspend
 * method from any dispatcher; Retrofit handles the IO dispatcher internally.
 */
class SandboxRepository(
    private val api: SandboxApi = SandboxClient.api,
) {
    private val mutex = Mutex()
    private var sessionId: String? = null

    /** Returns the current session id, creating one if necessary. */
    suspend fun ensureSession(): String = mutex.withLock {
        sessionId ?: api.createSession().sessionId.also { sessionId = it }
    }

    /** Drops the cached session id so the next call re-creates it. */
    suspend fun resetSession() = mutex.withLock {
        sessionId = null
    }

    /**
     * Evaluate [code] in the current session. On a 404 (session reaped on the server),
     * a fresh session is created exactly once and the call is retried.
     */
    suspend fun eval(code: String): EvalResponse =
        withSession { id -> api.eval(id, EvalRequest(code)) }

    suspend fun quickload(system: String): QuickloadResponse =
        withSession { id -> api.quickload(id, QuickloadRequest(system)) }

    suspend fun health(): HealthResponse = api.health()

    private suspend fun <T> withSession(block: suspend (String) -> Response<T>): T {
        val id = ensureSession()
        val response = block(id)
        if (response.code() == 404) {
            resetSession()
            val retryId = ensureSession()
            val retry = block(retryId)
            return retry.bodyOrThrow()
        }
        return response.bodyOrThrow()
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpException(this)
        return body() ?: throw IllegalStateException("Empty body for ${raw().request.url}")
    }
}
