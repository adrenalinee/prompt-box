package mystix.prompt.llm.google

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

class GoogleInteractionsHttpClientTest {
    private val jsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stream should parse sse events and send expected request payload`() {
        val sseBody = """
            data: {"event_type":"interaction.created","interaction":{"id":"inter_1","model":"gemini-2.5-flash","status":"in_progress"}}
            
            data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hello"}}
            
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val client = GoogleInteractionsHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = GoogleInteractionsStreamRequest(
            model = "gemini-2.5-flash",
            input = GoogleInteractionInput.Steps(
                listOf(
                    GoogleInteractionStep.UserInput(
                        listOf(GoogleInteractionContent.Text("hello")),
                    ),
                    GoogleInteractionStep.ModelOutput(
                        listOf(GoogleInteractionContent.Text("hi")),
                    ),
                    GoogleInteractionStep.FunctionResult(
                        callId = "call_1",
                        name = "lookup",
                        result = listOf(GoogleInteractionContent.Text("{\"ok\":true}")),
                    ),
                )
            ),
            systemInstruction = "You are concise.",
            store = false,
            generationConfig = GoogleInteractionGenerationConfig(
                temperature = 0.2,
                topP = 0.9,
                maxOutputTokens = 128,
                thinkingLevel = "high",
                thinkingSummaries = "auto",
                toolChoice = GoogleInteractionToolChoice.AllowedTools(
                    mode = "any",
                    tools = listOf("lookup"),
                ),
            ),
            tools = listOf(
                GoogleInteractionTool.Function(
                    name = "lookup",
                    description = "search keyword",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "검색어",
                            ),
                        ),
                        "required" to listOf("query"),
                    ),
                )
            ),
            responseFormat = GoogleInteractionResponseFormat.Text(
                mimeType = "application/json",
            ),
            previousInteractionId = "inter_prev",
        )

        val events = client.stream(request).collectList().block().orEmpty()
        assertEquals(2, events.size)
        assertEquals("interaction.created", events[0].eventType)
        assertEquals("step.delta", events[1].eventType)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/interactions", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-goog-api-key"))
        assertEquals("2026-05-20", recorded.getHeader("Api-Revision"))

        val requestJson = jsonMapper.readTree(recorded.body.readUtf8())
        assertEquals("gemini-2.5-flash", requestJson.path("model").asString())
        assertTrue(requestJson.path("stream").asBoolean())
        assertFalse(requestJson.path("store").asBoolean())
        assertEquals("inter_prev", requestJson.path("previous_interaction_id").asString())
        assertEquals("You are concise.", requestJson.path("system_instruction").asString())
        assertEquals("user_input", requestJson.path("input")[0].path("type").asString())
        assertEquals("hello", requestJson.path("input")[0].path("content")[0].path("text").asString())
        assertEquals("model_output", requestJson.path("input")[1].path("type").asString())
        assertEquals("function_result", requestJson.path("input")[2].path("type").asString())
        assertEquals("call_1", requestJson.path("input")[2].path("call_id").asString())
        assertEquals("{\"ok\":true}", requestJson.path("input")[2].path("result")[0].path("text").asString())
        assertEquals(0.2, requestJson.path("generation_config").path("temperature").asDouble())
        assertEquals(0.9, requestJson.path("generation_config").path("top_p").asDouble())
        assertEquals(128L, requestJson.path("generation_config").path("max_output_tokens").asLong())
        assertEquals("high", requestJson.path("generation_config").path("thinking_level").asString())
        assertEquals("auto", requestJson.path("generation_config").path("thinking_summaries").asString())
        assertEquals(
            "lookup",
            requestJson.path("generation_config")
                .path("tool_choice")
                .path("allowed_tools")
                .path("tools")[0]
                .asString()
        )
        assertEquals("lookup", requestJson.path("tools")[0].path("name").asString())
        assertEquals("function", requestJson.path("tools")[0].path("type").asString())
        assertEquals("query", requestJson.path("tools")[0].path("parameters").path("required")[0].asString())
        assertEquals("text", requestJson.path("response_format").path("type").asString())
        assertEquals("application/json", requestJson.path("response_format").path("mime_type").asString())
    }

    @Test
    fun `stream should map error response to GoogleApiException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                        {"error":{"code":400,"message":"input is required","status":"INVALID_ARGUMENT"}}
                    """.trimIndent()
                )
        )

        val client = GoogleInteractionsHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = GoogleInteractionsStreamRequest(
            model = "gemini-2.5-flash",
            input = GoogleInteractionInput.Steps(
                listOf(GoogleInteractionStep.UserInput(listOf(GoogleInteractionContent.Text("hi"))))
            ),
            systemInstruction = null,
            store = null,
            generationConfig = null,
            tools = emptyList(),
            responseFormat = null,
            previousInteractionId = null,
        )

        val thrown = assertThrows<Throwable> {
            client.stream(request).collectList().block()
        }

        val apiEx = when (thrown) {
            is GoogleApiException -> thrown
            else -> thrown.cause as? GoogleApiException
        }

        assertNotNull(apiEx)
        assertEquals(400, apiEx!!.statusCode)
        assertTrue(apiEx.message.contains("input is required"))
    }
}
