package com.landolisp.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.landolisp.data.api.SandboxApi
import com.landolisp.data.model.LandolispJson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

/**
 * Behavioural test for [SandboxRepository] backed by a real Retrofit binding
 * pointed at a [MockWebServer]. Verifies the three contracts the rest of the
 * app relies on:
 *
 *  1. The session id is created lazily on the first call that needs one.
 *  2. A 404 (session reaped server-side) drops the cached session id and
 *     transparently retries with a fresh one.
 *  3. Other non-success statuses surface as [HttpException] (mapped from the
 *     unsuccessful Retrofit response).
 */
class SandboxRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SandboxRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(LandolispJson.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(SandboxApi::class.java)
        repo = SandboxRepository(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun ensureSession_createsLazilyAndCaches() = runBlocking {
        server.enqueue(sessionCreatedResponse("sess-1"))

        val first = repo.ensureSession()
        val second = repo.ensureSession()

        assertEquals("sess-1", first)
        assertEquals("sess-1", second)
        // Only one POST /v1/sessions should have been issued.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun eval_retriesAfter404ByCreatingFreshSession() = runBlocking {
        // Initial session creation.
        server.enqueue(sessionCreatedResponse("sess-old"))
        // First eval: server says session is gone.
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"session_not_found","message":"gone"}"""),
        )
        // Repo should re-create a session.
        server.enqueue(sessionCreatedResponse("sess-new"))
        // Retried eval succeeds.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"stdout":"","stderr":"","value":"3","elapsedMs":1,"condition":null}"""),
        )

        val result = repo.eval("(+ 1 2)")
        assertEquals("3", result.value)

        // Confirm the path + method sequence.
        val r1 = server.takeRequest()
        assertEquals("/v1/sessions", r1.path); assertEquals("POST", r1.method)
        val r2 = server.takeRequest()
        assertEquals("/v1/sessions/sess-old/eval", r2.path)
        val r3 = server.takeRequest()
        assertEquals("/v1/sessions", r3.path); assertEquals("POST", r3.method)
        val r4 = server.takeRequest()
        assertEquals("/v1/sessions/sess-new/eval", r4.path)
    }

    @Test
    fun eval_throwsHttpExceptionOnNon404Failure() = runBlocking {
        server.enqueue(sessionCreatedResponse("sess-x"))
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"internal_error","message":"oops"}"""),
        )

        try {
            repo.eval("(+ 1 2)")
            fail("Expected HttpException for 500 status")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
    }

    @Test
    fun quickload_disallowedSurfacesAsHttpException() = runBlocking {
        server.enqueue(sessionCreatedResponse("sess-y"))
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"system_not_allowed","message":"no"}"""),
        )

        try {
            repo.quickload("evil")
            fail("Expected HttpException for 403 status")
        } catch (e: HttpException) {
            assertEquals(403, e.code())
            assertTrue(e.message()?.isNotEmpty() == true)
        }
    }

    @Test
    fun resetSession_dropsCachedIdSoNextCallReCreates() = runBlocking {
        server.enqueue(sessionCreatedResponse("sess-1"))
        repo.ensureSession()
        repo.resetSession()
        server.enqueue(sessionCreatedResponse("sess-2"))

        val next = repo.ensureSession()
        assertEquals("sess-2", next)
        assertEquals(2, server.requestCount)
    }

    private fun sessionCreatedResponse(id: String): MockResponse =
        MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"sessionId":"$id","expiresAt":"2099-01-01T00:00:00Z"}""")
}
