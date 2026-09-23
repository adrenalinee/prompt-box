package mystix.prompt.llm.google

import mystix.prompt.llm.LlmFileSearchTool
import mystix.prompt.llm.LlmMessageInputItem
import mystix.prompt.llm.LlmStreamEvent
import mystix.prompt.llm.LlmStreamOptions
import mystix.prompt.llm.LlmStreamRequest
import mystix.prompt.llm.ToolCallback
import mystix.prompt.llm.exception.InputEmptyException
import mystix.prompt.llm.exception.UnsupportedToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

class GoogleLlmStreamClient3Test {
    private val jsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }

    @Test
    fun `streams text interactions into common events and sends previous interaction id`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(
            mutableListOf(
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-1","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.start",
                        """{"event_type":"step.start","index":0,"step":{"type":"model_output"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hel"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"lo"}}""",
                    ),
                    rawEvent(
                        "step.stop",
                        """{"event_type":"step.stop","index":0}""",
                    ),
                    rawEvent(
                        "interaction.completed",
                        """
                            {
                              "event_type":"interaction.completed",
                              "interaction":{
                                "id":"inter-1",
                                "model":"gemini-2.5-flash",
                                "status":"completed",
                                "usage":{
                                  "total_input_tokens":10,
                                  "total_cached_tokens":0,
                                  "total_output_tokens":2,
                                  "total_thought_tokens":1,
                                  "total_tokens":13
                                }
                              }
                            }
                        """.trimIndent(),
                    ),
                )
            )
        )
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(), jsonMapper)

        val emitted = client.stream(
            LlmStreamRequest(
                previousResponseId = "inter-prev",
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "hi")),
                options = LlmStreamOptions(
                    temperature = 0.2,
                    topP = 0.9,
                    includeThoughts = true,
                    maxTokens = 128,
                    textFormat = "json_object",
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
            emitted.map { it.type },
        )
        assertEquals("hello", emitted.filterIsInstance<LlmStreamEvent.MessageContentPartDone>().single().text)
        val completed = emitted.filterIsInstance<LlmStreamEvent.ResponseCompleted>().single()
        assertEquals(10L, completed.inputTokens)
        assertEquals(2L, completed.outputTokens)
        assertEquals(1L, completed.reasoningTokens)
        assertEquals(13L, completed.totalTokens)

        val sent = rawClient.requests.single()
        assertEquals("inter-prev", sent.previousInteractionId)
        assertEquals("sys", sent.systemInstruction)
        assertEquals(0.2, sent.generationConfig?.temperature)
        assertEquals(0.9, sent.generationConfig?.topP)
        assertEquals(128L, sent.generationConfig?.maxOutputTokens)
        assertEquals("auto", sent.generationConfig?.thinkingSummaries)
        assertEquals("application/json", (sent.responseFormat as GoogleInteractionResponseFormat.Text).mimeType)
        val steps = (sent.input as GoogleInteractionInput.Steps).steps
        val userInput = steps.single() as GoogleInteractionStep.UserInput
        assertEquals("hi", (userInput.content.single() as GoogleInteractionContent.Text).text)
    }

    @Test
    fun `tool result is sent as function_result with previous interaction id`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(
            mutableListOf(
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-tool","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.start",
                        """{"event_type":"step.start","index":0,"step":{"type":"function_call","id":"call-1","name":"lookup","arguments":{"query":"seoul"}}}""",
                    ),
                    rawEvent(
                        "step.stop",
                        """{"event_type":"step.stop","index":0}""",
                    ),
                    rawEvent(
                        "interaction.completed",
                        """{"event_type":"interaction.completed","interaction":{"id":"inter-tool","status":"requires_action","usage":{"total_input_tokens":4,"total_output_tokens":1,"total_tokens":5}}}""",
                    ),
                ),
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-final","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"done"}}""",
                    ),
                    rawEvent(
                        "step.stop",
                        """{"event_type":"step.stop","index":0}""",
                    ),
                    rawEvent(
                        "interaction.completed",
                        """{"event_type":"interaction.completed","interaction":{"id":"inter-final","status":"completed","usage":{"total_input_tokens":5,"total_output_tokens":1,"total_tokens":6}}}""",
                    ),
                ),
            )
        )
        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("result" to args.query.uppercase())) }
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)

        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go")),
            )
        ).collectList().block().orEmpty()

        assertEquals(
            listOf(
                "response.created",
                "response.function_call.done",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            emitted.map { it.type },
        )
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>().single()
        assertEquals("call-1", observed.callId)
        assertEquals("{\"query\":\"seoul\"}", observed.argumentsJson)
        assertEquals("{\"result\":\"SEOUL\"}", observed.output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL, observed.delivery)
        val completed = emitted.filterIsInstance<LlmStreamEvent.ResponseCompleted>().single()
        assertEquals(9L, completed.inputTokens)
        assertEquals(2L, completed.outputTokens)
        assertEquals(11L, completed.totalTokens)

        assertEquals(2, rawClient.requests.size)
        val second = rawClient.requests[1]
        assertEquals("inter-tool", second.previousInteractionId)
        val functionResult = (second.input as GoogleInteractionInput.Steps)
            .steps
            .single() as GoogleInteractionStep.FunctionResult
        assertEquals("call-1", functionResult.callId)
        assertEquals("lookup", functionResult.name)
        assertEquals("{\"result\":\"SEOUL\"}", (functionResult.result.single() as GoogleInteractionContent.Text).text)
    }

    @Test
    fun `tool call waits for streamed argument deltas`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(
            mutableListOf(
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-tool","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.start",
                        """{"event_type":"step.start","index":0,"step":{"type":"function_call","id":"call-1","name":"lookup"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"arguments_delta","arguments":"{\"query\":\"seo"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"arguments_delta","arguments":"ul\"}"}}""",
                    ),
                    rawEvent(
                        "step.stop",
                        """{"event_type":"step.stop","index":0}""",
                    ),
                ),
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-final","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.delta",
                        """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"done"}}""",
                    ),
                    rawEvent(
                        "step.stop",
                        """{"event_type":"step.stop","index":0}""",
                    ),
                ),
            )
        )
        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("result" to args.query.uppercase())) }
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)

        val emitted = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "go")))
        ).collectList().block().orEmpty()

        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>().single()
        assertEquals("call-1", observed.callId)
        assertEquals("{\"query\":\"seoul\"}", observed.argumentsJson)
        assertEquals("{\"result\":\"SEOUL\"}", observed.output)
    }

    @Test
    fun `tool returnDirect emits synthetic message and does not call follow-up interaction`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(
            mutableListOf(
                Flux.just(
                    rawEvent(
                        "interaction.created",
                        """{"event_type":"interaction.created","interaction":{"id":"inter-tool","model":"gemini-2.5-flash","status":"in_progress"}}""",
                    ),
                    rawEvent(
                        "step.start",
                        """{"event_type":"step.start","index":0,"step":{"type":"function_call","id":"call-direct","name":"directEcho","arguments":{"value":"ping"}}}""",
                    ),
                )
            )
        )
        val tool = ToolCallback(
            name = "directEcho",
            description = "",
            inputType = EchoArgs::class,
            returnDirect = true,
        ) { args -> Mono.just("direct:${args.value}") }
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)

        val emitted = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "run")))
        ).collectList().block().orEmpty()

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
            emitted.map { it.type },
        )
        assertEquals(1, rawClient.requests.size)
        assertTrue(emitted.filterIsInstance<LlmStreamEvent.MessageDelta>().any { it.delta == "direct:ping" })
    }

    @Test
    fun `fails when request has no usable inputs`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(mutableListOf())
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(), jsonMapper)

        StepVerifier.create(client.stream(LlmStreamRequest()))
            .expectErrorSatisfies { error -> assertTrue(error is InputEmptyException) }
            .verify()
    }

    @Test
    fun `fails when built-in tool is requested`() {
        val rawClient = RecordingGoogleInteractionsStreamClient(mutableListOf())
        val client = GoogleLlmStreamClient3(rawClient, LlmStreamRequest(), jsonMapper)

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

    data class LookupArgs(
        val query: String,
    )

    data class EchoArgs(
        val value: String,
    )

    private class RecordingGoogleInteractionsStreamClient(
        private val queuedResponses: MutableList<Flux<GoogleInteractionRawStreamEvent>>,
    ) : GoogleInteractionsStreamClient {
        val requests = mutableListOf<GoogleInteractionsStreamRequest>()

        override fun stream(request: GoogleInteractionsStreamRequest): Flux<GoogleInteractionRawStreamEvent> {
            requests.add(request)
            return if (queuedResponses.isEmpty()) {
                Flux.empty()
            } else {
                queuedResponses.removeAt(0)
            }
        }
    }

    private fun rawEvent(
        eventType: String,
        payload: String,
    ): GoogleInteractionRawStreamEvent =
        GoogleInteractionRawStreamEvent(
            eventType = eventType,
            payload = payload,
        )
}
