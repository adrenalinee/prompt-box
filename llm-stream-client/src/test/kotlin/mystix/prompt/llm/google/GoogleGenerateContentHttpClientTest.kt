package mystix.prompt.llm.google

import mystix.prompt.llm.google.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper
import kotlin.collections.get

class GoogleGenerateContentHttpClientTest {
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
            data: {"responseId":"resp_1","modelVersion":"gemini-2.5-flash","candidates":[{"content":{"parts":[{"text":"hel"},{"text":"lo"},{"functionCall":{"id":"fc1","name":"lookup","args":{"q":"x"}}}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":13}}
            
            data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}]}
            
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val client = GoogleGenerateContentHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = GoogleGenerateContentStreamRequest(
            model = "gemini-2.5-flash",
            contents = listOf(
                GoogleContent(
                    role = "user",
                    parts = listOf(GooglePart.Text("hello")),
                )
            ),
            systemInstruction = "You are concise.",
            temperature = 0.2,
            topP = 0.9,
            topK = 10.0,
            includeThoughts = true,
            maxOutputTokens = 128,
            tools = listOf(
                GoogleFunctionDeclaration(
                    name = "lookup",
                    description = "search keyword",
                    parametersJsonSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf(
                                "type" to "string",
                                "description" to "검색어",
                            ),
                            "limit" to mapOf(
                                "type" to "integer",
                                "nullable" to true,
                            ),
                        ),
                        "required" to listOf("query"),
                        "additionalProperties" to false,
                    ),
                )
            ),
            toolChoiceMode = GoogleFunctionCallingMode.ANY,
        )

        val chunks = client.stream(request).collectList().block()
        assertNotNull(chunks)
        assertEquals(2, chunks!!.size)

        assertEquals("resp_1", chunks[0].responseId)
        assertEquals("gemini-2.5-flash", chunks[0].modelVersion)
        // 첫 청크의 parts: text "hel", text "lo", functionCall lookup — 순서가 보존되어야 함.
        val firstParts = chunks[0].parts
        assertEquals(3, firstParts.size)
        assertEquals("hel", (firstParts[0] as GoogleStreamPart.Text).text)
        assertEquals("lo", (firstParts[1] as GoogleStreamPart.Text).text)
        val fc = (firstParts[2] as GoogleStreamPart.FunctionCall).call
        assertEquals("lookup", fc.name)
        assertEquals("x", fc.args["q"])
        assertEquals(10L, chunks[0].usage?.promptTokenCount)
        assertEquals(3L, chunks[0].usage?.candidatesTokenCount)
        assertEquals(13L, chunks[0].usage?.totalTokenCount)

        val secondParts = chunks[1].parts
        assertEquals(1, secondParts.size)
        assertEquals(" world", (secondParts[0] as GoogleStreamPart.Text).text)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            recorded.path,
        )
        assertEquals("test-key", recorded.getHeader("x-goog-api-key"))

        val requestJson = jsonMapper.readTree(recorded.body.readUtf8())
        assertEquals("hello", requestJson.path("contents")[0].path("parts")[0].path("text").asText())
        assertEquals("You are concise.", requestJson.path("systemInstruction").path("parts")[0].path("text").asText())
        assertEquals(0.2, requestJson.path("generationConfig").path("temperature").asDouble())
        assertEquals(0.9, requestJson.path("generationConfig").path("topP").asDouble())
        assertEquals(10.0, requestJson.path("generationConfig").path("topK").asDouble())
        assertEquals(128L, requestJson.path("generationConfig").path("maxOutputTokens").asLong())
        assertTrue(requestJson.path("thinkingConfig").path("includeThoughts").asBoolean())
        assertEquals("lookup", requestJson.path("tools")[0].path("functionDeclarations")[0].path("name").asText())
        assertEquals(
            "검색어",
            requestJson.path("tools")[0].path("functionDeclarations")[0]
                .path("parametersJsonSchema")
                .path("properties")
                .path("query")
                .path("description")
                .asText()
        )
        assertEquals(
            "query",
            requestJson.path("tools")[0].path("functionDeclarations")[0]
                .path("parametersJsonSchema")
                .path("required")[0]
                .asText()
        )
        assertEquals("ANY", requestJson.path("toolConfig").path("functionCallingConfig").path("mode").asText())
    }

    @Test
    fun `stream should normalize model path when model prefix is missing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n")
        )

        val client = GoogleGenerateContentHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = GoogleGenerateContentStreamRequest(
            model = "gemini-2.0-flash",
            contents = listOf(GoogleContent(role = "user", parts = listOf(GooglePart.Text("hi")))),
            systemInstruction = null,
            temperature = null,
            topP = null,
            topK = null,
            includeThoughts = null,
            maxOutputTokens = null,
            tools = emptyList(),
            toolChoiceMode = null,
        )

        client.stream(request).collectList().block()

        val recorded = server.takeRequest()
        assertEquals(
            "/v1beta/models/gemini-2.0-flash:streamGenerateContent?alt=sse",
            recorded.path,
        )
    }

    @Test
    fun `stream should map error response to GoogleApiException`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {"error":{"code":400,"message":"contents is not specified","status":"INVALID_ARGUMENT"}}
                """.trimIndent())
        )

        val client = GoogleGenerateContentHttpClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            jsonMapper = jsonMapper,
        )

        val request = GoogleGenerateContentStreamRequest(
            model = "models/gemini-2.5-flash",
            contents = listOf(GoogleContent(role = "user", parts = listOf(GooglePart.Text("hi")))),
            systemInstruction = null,
            temperature = null,
            topP = null,
            topK = null,
            includeThoughts = null,
            maxOutputTokens = null,
            tools = emptyList(),
            toolChoiceMode = null,
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
        assertTrue(apiEx.message.contains("contents is not specified"))
    }
}
