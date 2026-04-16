package com.landolisp.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.landolisp.data.model.EvalRequest
import com.landolisp.data.model.LandolispJson
import com.landolisp.data.model.QuickloadRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Wire-level contract test for [SandboxApi].
 *
 * Spins up a [MockWebServer] per test, feeds the Retrofit interface canned
 * responses cribbed verbatim from `docs/API.md`, and asserts that:
 *  - the request URL, method, headers, and body match the documented shape;
 *  - the response is decoded into the corresponding DTO with all fields intact;
 *  - error statuses (4xx, 408) surface as non-success [retrofit2.Response]s
 *    that the repository can branch on.
 *
 * No real network IO; the server is bound to localhost on an ephemeral port.
 */
class SandboxApiContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SandboxApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(LandolispJson.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(SandboxApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- /v1/sessions ---------------------------------------------------

    @Test
    fun createSession_decodesSessionResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessionId":"9f3...e2","expiresAt":"2026-04-16T14:00:00Z"}"""),
        )

        val resp = api.createSession()

        assertEquals("9f3...e2", resp.sessionId)
        assertEquals("2026-04-16T14:00:00Z", resp.expiresAt)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sessions", recorded.path)
    }

    // ---- eval (success) -------------------------------------------------

    @Test
    fun eval_success_serializesRequestAndDecodesResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"stdout":"","stderr":"","value":"3","elapsedMs":4,"condition":null}""",
                ),
        )

        val response = api.eval("abc-123", EvalRequest("(+ 1 2)"))
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals("3", body.value)
        assertEquals("", body.stdout)
        assertEquals("", body.stderr)
        assertEquals(4L, body.elapsedMs)
        assertNull(body.condition)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sessions/abc-123/eval", recorded.path)
        assertTrue(recorded.headers["Content-Type"]?.contains("application/json") == true)
        assertEquals("""{"code":"(+ 1 2)"}""", recorded.body.readUtf8())
    }

    // ---- eval (timeout 408) --------------------------------------------

    @Test
    fun eval_timeout_returns408Response() = runBlocking {
        val body = "{\"stdout\":\"\",\"stderr\":\"\",\"value\":null,\"elapsedMs\":10000," +
            "\"condition\":{\"type\":\"EVAL-TIMEOUT\",\"message\":\"evaluation exceeded 10 seconds\"}}"
        server.enqueue(
            MockResponse()
                .setResponseCode(408)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )

        val response = api.eval("sid", EvalRequest("(loop)"))
        assertEquals(408, response.code())
        // Even on 408 the server returns a JSON body; Retrofit puts it in errorBody.
        // We don't decode it here, but assert presence so the repo layer can.
        assertNotNull(response.errorBody())
    }

    // ---- eval (condition with non-null condition) ----------------------

    @Test
    fun eval_userError_decodesConditionField() = runBlocking {
        val body = "{\"stdout\":\"\",\"stderr\":\"\",\"value\":null,\"elapsedMs\":2," +
            "\"condition\":{\"type\":\"SIMPLE-ERROR\",\"message\":\"boom\"}}"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )

        val response = api.eval("sid", EvalRequest("(error \"boom\")"))
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertNull(body.value)
        val cond = body.condition
        assertNotNull(cond)
        assertEquals("SIMPLE-ERROR", cond!!.type)
        assertEquals("boom", cond.message)
    }

    // ---- quickload (success) -------------------------------------------

    @Test
    fun quickload_success() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"loaded":true,"log":";; loaded alexandria"}"""),
        )

        val response = api.quickload("sid", QuickloadRequest("alexandria"))
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertTrue(body.loaded)
        assertTrue(body.log.contains("alexandria"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sessions/sid/quickload", recorded.path)
        assertEquals("""{"system":"alexandria"}""", recorded.body.readUtf8())
    }

    // ---- quickload disallowed (4xx) ------------------------------------

    @Test
    fun quickload_disallowed_returns403() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"system_not_allowed","message":"system \"evil\" not allowed"}"""),
        )

        val response = api.quickload("sid", QuickloadRequest("evil"))
        assertEquals(403, response.code())
        assertFalse(response.isSuccessful)
    }

    // ---- files: list ---------------------------------------------------

    @Test
    fun listFiles_decodesArray() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"path":"foo.lisp","size":12},{"path":"sub/bar.lisp","size":256}]"""),
        )

        val response = api.listFiles("sid")
        assertTrue(response.isSuccessful)
        val body = response.body()!!
        assertEquals(2, body.size)
        assertEquals("foo.lisp", body[0].path)
        assertEquals(12L, body[0].size)
        assertEquals("sub/bar.lisp", body[1].path)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/sessions/sid/files", recorded.path)
    }

    // ---- files: put ----------------------------------------------------

    @Test
    fun writeFile_sendsRawText_returns204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val body = "(defun greet () :hi)".toRequestBody("text/plain".toMediaType())
        val response = api.writeFile("sid", "greet.lisp", body)

        assertEquals(204, response.code())
        val recorded: RecordedRequest = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/v1/sessions/sid/files/greet.lisp", recorded.path)
        assertEquals("(defun greet () :hi)", recorded.body.readUtf8())
    }

    // ---- files: get raw text ------------------------------------------

    @Test
    fun readFile_returnsRawText() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody("(defun greet () :hi)"),
        )

        val response = api.readFile("sid", "greet.lisp")
        assertTrue(response.isSuccessful)
        assertEquals("(defun greet () :hi)", response.body()?.string())

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/sessions/sid/files/greet.lisp", recorded.path)
    }

    // ---- files: delete -------------------------------------------------

    @Test
    fun deleteFile_returns204() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val response = api.deleteFile("sid", "greet.lisp")

        assertEquals(204, response.code())
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/v1/sessions/sid/files/greet.lisp", recorded.path)
    }

    // ---- health --------------------------------------------------------

    @Test
    fun health_decodesAllFields() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"ok","sbclVersion":"2.4.0","qlDist":"quicklisp 2024-06-01"}""",
                ),
        )

        val resp = api.health()
        assertEquals("ok", resp.status)
        assertEquals("2.4.0", resp.sbclVersion)
        assertEquals("quicklisp 2024-06-01", resp.qlDist)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/health", recorded.path)
    }
}
