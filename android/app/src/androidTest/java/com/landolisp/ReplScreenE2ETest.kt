package com.landolisp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.landolisp.data.SandboxRepository
import com.landolisp.data.api.SandboxApi
import com.landolisp.data.model.LandolispJson
import com.landolisp.ui.theme.LandolispTheme
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/**
 * End-to-end smoke for the REPL flow against a [MockWebServer]. Rather than
 * pull the full [com.landolisp.ui.ReplScreen] (which constructs its own
 * ViewModel against [com.landolisp.data.api.SandboxClient.api]), this test
 * builds a minimal Compose harness around [SandboxRepository] pointed at the
 * mock server. That keeps us strictly inside an instrumentation test and
 * exercises the same Retrofit + DTO path the production REPL uses.
 *
 * If/when B1 lands a `ReplScreen(viewModelOverride =)` hook the harness can
 * be replaced with a single ReplScreen invocation, which is the cleaner
 * E2E. See ANDROID notes for the planned refactor.
 */
class ReplScreenE2ETest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var repo: SandboxRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Session create + one successful eval.
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sessionId":"test-sess","expiresAt":"2099-01-01T00:00:00Z"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"stdout":"","stderr":"","value":"3","elapsedMs":1,"condition":null}""",
                ),
        )

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
    fun evalRoundTrip_throughRepository_returnsExpectedValue() = runBlocking {
        // The repository call is the integration-critical bit; if Retrofit's
        // DTO mapping or session retry breaks against a real network, this
        // is the layer that catches it.
        val r1 = repo.eval("(+ 1 2)")
        org.junit.Assert.assertEquals("3", r1.value)
        org.junit.Assert.assertEquals(2, server.requestCount) // session + eval
    }

    @Test
    fun typingCode_andTappingSend_showsResponse_inCustomReplHarness() {
        composeRule.setContent {
            LandolispTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MockReplHarness(repo)
                }
            }
        }

        // The harness exposes a single TextField + a Send button.
        composeRule.onNode(hasSetTextAction()).performTextInput("(+ 1 2)")
        composeRule.onNodeWithText("Send").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("=> 3", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("=> 3", substring = true).assertIsDisplayed()
    }
}
