package malibu.llm.streamclient.xai

import malibu.llm.streamclient.xai.XaiApiException
import malibu.llm.streamclient.xai.XaiFileSearchTool
import malibu.llm.streamclient.xai.XaiFunctionTool
import malibu.llm.streamclient.xai.XaiInputItem
import malibu.llm.streamclient.xai.XaiReasoningConfiguration
import malibu.llm.streamclient.xai.XaiResponsesHttpClient
import malibu.llm.streamclient.xai.XaiResponsesStreamRequest
import malibu.llm.streamclient.xai.XaiTextResponseConfiguration
import malibu.llm.streamclient.xai.XaiToolChoice
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

class XaiResponsesHttpClientTest {
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
    fun `stream should parse sse chunks and send expected request payload`() {
        val sseBody = """
            data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","model":"grok-2"}}
            
            data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"hel"}
            
            data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"lo"}
            
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val client = XaiResponsesHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = XaiResponsesStreamRequest(
            model = "grok-2",
            input = listOf(
                XaiInputItem.Message(
                    role = "user",
                    content = "hello",
                )
            ),
            instructions = "You are concise.",
            store = false,
            temperature = 0.2,
            topP = 0.9,
            maxOutputTokens = 128,
            include = listOf("reasoning.encrypted_content"),
            reasoning = XaiReasoningConfiguration(
                effort = "high",
                summary = "detailed",
            ),
            text = XaiTextResponseConfiguration(
                format = mapOf("type" to "json_object"),
            ),
            tools = listOf(
                XaiFunctionTool(
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
                        "additionalProperties" to false,
                    ),
                ),
                XaiFileSearchTool(
                    vectorStoreIds = listOf("vs_123"),
                    maxNumResults = 5,
                ),
            ),
            toolChoice = XaiToolChoice.Function("lookup"),
            previousResponseId = "resp_prev",
        )

        val chunks = client.stream(request).collectList().block().orEmpty()
        assertEquals(3, chunks.size)
        assertEquals("response.created", chunks[0].type)
        assertEquals("response.output_text.delta", chunks[1].type)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/responses", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))

        val requestJson = jsonMapper.readTree(recorded.body.readUtf8())
        assertEquals("grok-2", requestJson.path("model").asText())
        assertTrue(requestJson.path("stream").asBoolean())
        assertEquals("hello", requestJson.path("input")[0].path("content").asText())
        assertEquals("You are concise.", requestJson.path("instructions").asText())
        assertFalse(requestJson.path("store").asBoolean())
        assertEquals(0.2, requestJson.path("temperature").asDouble())
        assertEquals(0.9, requestJson.path("top_p").asDouble())
        assertEquals(128L, requestJson.path("max_output_tokens").asLong())
        assertEquals("reasoning.encrypted_content", requestJson.path("include")[0].asText())
        assertEquals("high", requestJson.path("reasoning").path("effort").asText())
        assertEquals("detailed", requestJson.path("reasoning").path("summary").asText())
        assertEquals("json_object", requestJson.path("text").path("format").path("type").asText())
        assertEquals("lookup", requestJson.path("tools")[0].path("name").asText())
        assertEquals(
            "검색어",
            requestJson.path("tools")[0].path("parameters").path("properties").path("query").path("description").asText()
        )
        assertEquals("query", requestJson.path("tools")[0].path("parameters").path("required")[0].asText())
        assertEquals("file_search", requestJson.path("tools")[1].path("type").asText())
        assertEquals("vs_123", requestJson.path("tools")[1].path("vector_store_ids")[0].asText())
        assertEquals(5L, requestJson.path("tools")[1].path("max_num_results").asLong())
        assertEquals("function", requestJson.path("tool_choice").path("type").asText())
        assertEquals("lookup", requestJson.path("tool_choice").path("name").asText())
        assertEquals("resp_prev", requestJson.path("previous_response_id").asText())
    }

    @Test
    fun `stream should map error response to XaiApiException with raw body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {"error":"unsupported parameter: reasoning","message":"unsupported parameter: reasoning"}
                """.trimIndent())
        )

        val client = XaiResponsesHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = XaiResponsesStreamRequest(
            model = "grok-2",
            input = listOf(XaiInputItem.Message(role = "user", content = "hi")),
            instructions = null,
            store = null,
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            include = emptyList(),
            reasoning = null,
            text = null,
            tools = emptyList(),
            toolChoice = null,
            previousResponseId = null,
        )

        val thrown = assertThrows<Throwable> {
            client.stream(request).collectList().block()
        }

        val apiEx = when (thrown) {
            is XaiApiException -> thrown
            else -> thrown.cause as? XaiApiException
        }

        assertNotNull(apiEx)
        assertEquals(400, apiEx!!.statusCode)
        assertTrue(apiEx.message!!.contains("unsupported parameter"))
        assertTrue(apiEx.body.contains("reasoning"))
    }
}
