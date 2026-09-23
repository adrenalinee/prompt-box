package mystix.prompt.llm.google

import malibu.llm.streamclient.LlmMessageInputItem
import malibu.llm.streamclient.LlmFileSearchTool
import malibu.llm.streamclient.google.GoogleGenerateContentStreamClient
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.streamclient.LlmStreamOptions
import malibu.llm.streamclient.LlmStreamRequest
import malibu.llm.streamclient.google.GoogleFunctionCall
import malibu.llm.streamclient.google.GoogleGenerateContentStreamRequest
import malibu.llm.streamclient.google.GooglePart
import malibu.llm.streamclient.google.GoogleStreamChunk
import malibu.llm.streamclient.google.GoogleStreamPart
import malibu.llm.streamclient.google.GoogleUsage
import malibu.llm.streamclient.ToolCallback
import malibu.llm.streamclient.exception.InputEmptyException
import malibu.llm.streamclient.exception.UnsupportedToolException
import malibu.llm.streamclient.google.GoogleLlmStreamClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

class GoogleLlmStreamClientTest {
    private val jsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }

    @Test
    fun `streams text chunks into common events`() {
        val rawClient = RecordingGoogleStreamClient(
            mutableListOf(
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-1",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(GoogleStreamPart.Text("hel", thought = false)),
                        usage = null,
                    ),
                    GoogleStreamChunk(
                        responseId = "resp-1",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(GoogleStreamPart.Text("lo", thought = false)),
                        usage = GoogleUsage(
                            promptTokenCount = 10,
                            cachedContentTokenCount = 0,
                            candidatesTokenCount = 2,
                            thoughtsTokenCount = null,
                            totalTokenCount = 12,
                        ),
                    ),
                )
            )
        )

        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "hi", role = LlmMessageInputItem.Role.USER)),
                options = LlmStreamOptions(
                    topK = 42.0,
                    includeThoughts = true,
                ),
            )
        ).collectList().block().orEmpty()

        assertEquals(
            listOf(
                "response.created",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            emitted.map { it.type }
        )

        val done = emitted.first { it.type == "response.message.content_part.done" }
        val text = (done as LlmStreamEvent.MessageContentPartDone).text
        assertEquals("hello", text)

        val sentRequest = rawClient.requests.first()
        assertEquals(42.0, sentRequest.topK)
        assertTrue(sentRequest.includeThoughts == true)
    }

    @Test
    fun `tool returnDirect emits wrapped synthetic message and completes`() {
        val rawClient = RecordingGoogleStreamClient(
            mutableListOf(
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-1",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(
                            GoogleStreamPart.FunctionCall(
                                GoogleFunctionCall(
                                    id = "call-1",
                                    name = "directEcho",
                                    args = mapOf("value" to "ping"),
                                )
                            )
                        ),
                        usage = null,
                    )
                )
            )
        )

        val tool = ToolCallback(
            name = "directEcho",
            description = "",
            inputType = EchoArgs::class,
            returnDirect = true,
        ) { args -> Mono.just("direct:${args.value}") }

        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "run", role = LlmMessageInputItem.Role.USER)),
            )
        ).collectList().block().orEmpty()

        // ResponseCreated → FunctionCallObserved → 합성 메시지(message.added 로 감싸짐) → 종료. RETURN_DIRECT 는 ResponseCompleted 없음.
        assertEquals(
            listOf(
                "response.created",
                "response.function_call.done",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
            ),
            emitted.map { it.type }
        )
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals("{\"value\":\"ping\"}", observed[0].argumentsJson)
        assertEquals("direct:ping", observed[0].output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT, observed[0].delivery)
        val deltas = emitted.filterIsInstance<LlmStreamEvent.MessageDelta>()
        assertTrue(deltas.any { it.delta == "direct:ping" })
    }

    @Test
    fun `tool non-direct triggers second stream step with function response context`() {
        val rawClient = RecordingGoogleStreamClient(
            mutableListOf(
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-tool",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(
                            GoogleStreamPart.FunctionCall(
                                GoogleFunctionCall(
                                    id = "call-2",
                                    name = "lookup",
                                    args = mapOf("query" to "seoul"),
                                )
                            )
                        ),
                        usage = null,
                    )
                ),
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-final",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(GoogleStreamPart.Text("done", thought = false)),
                        usage = null,
                    )
                ),
            )
        )

        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("result" to args.query.uppercase())) }

        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go", role = LlmMessageInputItem.Role.USER)),
            )
        ).collectList().block().orEmpty()

        assertTrue(emitted.any { it.type == "response.message.delta" })
        val finalDelta = emitted.filterIsInstance<LlmStreamEvent.MessageDelta>().lastOrNull()
        assertEquals("done", finalDelta?.delta)
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals("{\"query\":\"seoul\"}", observed.single().argumentsJson)
        assertEquals("{\"result\":\"SEOUL\"}", observed.single().output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL, observed.single().delivery)
        // B안: 첫 라운드의 ResponseCreated 만 노출.
        assertEquals(
            listOf("resp-tool"),
            emitted.filterIsInstance<LlmStreamEvent.ResponseCreated>().map { it.id }
        )

        assertEquals(2, rawClient.requests.size)
        val secondRequest = rawClient.requests[1]
        assertTrue(secondRequest.contents.size >= 3)

        val modelCallContent = secondRequest.contents[secondRequest.contents.size - 2]
        assertEquals("model", modelCallContent.role)
        val functionCallPart = modelCallContent.parts.first() as GooglePart.FunctionCall
        assertEquals("lookup", functionCallPart.name)

        val functionResponseContent = secondRequest.contents.last()
        assertEquals("user", functionResponseContent.role)
        val functionResponsePart = functionResponseContent.parts.first() as GooglePart.FunctionResponse
        assertEquals("lookup", functionResponsePart.name)
        assertNotNull(functionResponsePart.response["output"])
    }

    @Test
    fun `text-A then function call then text-B preserves vendor ordering within one chunk`() {
        // Google 의 흔한 패턴: 한 응답의 parts 배열에 text + functionCall + text 가 인터리브됨.
        val rawClient = RecordingGoogleStreamClient(
            mutableListOf(
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-1",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(
                            GoogleStreamPart.Text("A...", thought = false),
                            GoogleStreamPart.FunctionCall(
                                GoogleFunctionCall(
                                    id = "call-x",
                                    name = "lookup",
                                    args = mapOf("query" to "x"),
                                )
                            ),
                        ),
                        usage = GoogleUsage(10, 0, 5, null, 15),
                    )
                ),
                Flux.just(
                    GoogleStreamChunk(
                        responseId = "resp-2",
                        modelVersion = "gemini-2.5-flash",
                        parts = listOf(GoogleStreamPart.Text("B...", thought = false)),
                        usage = GoogleUsage(20, 0, 3, null, 23),
                    )
                ),
            )
        )
        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("r" to args.query)) }
        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go")),
            )
        ).collectList().block().orEmpty()

        val types = emitted.map { it.type }
        // text-A 메시지가 닫힌 뒤 function call, 그 다음 새 메시지 아이템으로 text-B.
        assertEquals(
            listOf(
                "response.created",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.function_call.done",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            types,
            "types=$types",
        )

        // 첫 라운드의 ResponseCreated 만 노출, 토큰은 합산.
        val created = emitted.filterIsInstance<LlmStreamEvent.ResponseCreated>().single()
        assertEquals("resp-1", created.id)
        val completed = emitted.filterIsInstance<LlmStreamEvent.ResponseCompleted>().single()
        assertEquals(30L, completed.inputTokens)
        assertEquals(8L, completed.outputTokens)
        assertEquals(38L, completed.totalTokens)

        // itemIndex 가 라운드 경계를 가로질러 단조 증가 (function call 은 슬롯 미소비).
        val addedIndexes = emitted.filterIsInstance<LlmStreamEvent.ResponseMessageAdded>().map { it.itemIndex }
        assertEquals(listOf(0L, 1L), addedIndexes)
    }

    @Test
    fun `fails when request has no usable inputs`() {
        val rawClient = RecordingGoogleStreamClient(mutableListOf())
        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)

        StepVerifier.create(client.stream(LlmStreamRequest()))
            .expectErrorSatisfies { error ->
                assertTrue(error is InputEmptyException)
            }
            .verify()
    }

    @Test
    fun `fails when built-in tool is requested`() {
        val rawClient = RecordingGoogleStreamClient(mutableListOf())
        val client = GoogleLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)

        val error = assertThrows(UnsupportedToolException::class.java) {
            client.stream(
                LlmStreamRequest(
                    inputs = listOf(LlmMessageInputItem(content = "hi")),
                    tools = listOf(LlmFileSearchTool(vectorStoreIds = listOf("vs_123"))),
                )
            )
        }

        assertTrue(error.message!!.contains("provider: google"))
        assertTrue(error.message!!.contains("file_search"))
    }

    data class EchoArgs(
        val value: String,
    )

    data class LookupArgs(
        val query: String,
    )

    private class RecordingGoogleStreamClient(
        private val queuedResponses: MutableList<Flux<GoogleStreamChunk>>,
    ) : GoogleGenerateContentStreamClient {
        val requests = mutableListOf<GoogleGenerateContentStreamRequest>()

        override fun stream(request: GoogleGenerateContentStreamRequest): Flux<GoogleStreamChunk> {
            requests.add(request)
            return if (queuedResponses.isEmpty()) {
                Flux.empty()
            } else {
                queuedResponses.removeAt(0)
            }
        }
    }
}
