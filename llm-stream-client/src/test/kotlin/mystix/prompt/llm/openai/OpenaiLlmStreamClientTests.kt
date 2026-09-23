package mystix.prompt.llm.openai

import com.openai.models.responses.*
import io.mockk.every
import io.mockk.mockk
import malibu.llm.streamclient.LlmFileSearchTool
import malibu.llm.streamclient.LlmMessageInputItem
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.streamclient.LlmStreamOptions
import malibu.llm.streamclient.LlmStreamRequest
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.ToolCallback
import malibu.llm.streamclient.VerbosityType
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.jvm.optionals.getOrNull

class OpenaiLlmStreamClientTests {

    @Test
    fun streamsSimpleAssistantMessage() {
        val messageId = "msg_simple"
        val messageItem = messageOutputItem(messageId)

        val events = listOf(
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(messageId)
                    .delta("테스트 응답")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .logprobs(emptyList<ResponseTextDeltaEvent.Logprob>())
                    .build()
            ),
            ResponseStreamEvent.ofOutputTextDone(
                ResponseTextDoneEvent.builder()
                    .itemId(messageId)
                    .text("테스트 응답")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = messageId,
                text = "테스트 응답",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
        )

        val client = clientWithStreams(streams = listOf(FakeAsyncStreamResponse(events)))

        val outputs = client.stream(
            LlmStreamRequest(
                inputs = listOf(
                    LlmMessageInputItem(content = "안녕?"),
                ),
            )
        )

        val emitted = outputs.collectList().block().orEmpty()
        assertTrue(emitted.isNotEmpty())
        val types = emitted.map { it.type }
        assertEquals(
            listOf(
                "response.message.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
            ),
            types,
            "types=$types",
        )
    }

    @Test
    fun toolResultIsReturnedDirectlyWhenConfigured() {
        val callId = "direct_call"
        val toolName = "directEcho"
        val tool = ToolCallback(
            name = toolName,
            description = "직접 반환",
            inputType = ToolArgs::class,
            returnDirect = true,
        ) { args -> Mono.just("직접:${args.value}") }

        val client = clientWithStreams(
            tools = listOf(tool),
            streams = listOf(
                fakeStream(
                    ResponseStreamEvent.Companion.ofOutputItemDone(
                        ResponseOutputItemDoneEvent.builder()
                            .item(functionCallOutputItem(callId, toolName, "{\"value\":\"ping\"}"))
                            .outputIndex(0)
                            .sequenceNumber(0)
                            .build()
                    )
                )
            )
        )

        val outputs = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "direct?"))
            )
        )

        val emitted = outputs.collectList().block().orEmpty()
        assertTrue(emitted.isNotEmpty())
        // A안: 합성 메시지가 message.added 로 감싸짐. RETURN_DIRECT 는 ResponseCompleted 없음.
        assertEquals(
            listOf(
                "response.function_call.done",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
            ),
            emitted.map { it.type },
            "types=${emitted.map { it.type }}",
        )
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals("{\"value\":\"ping\"}", observed[0].argumentsJson)
        assertEquals("직접:ping", observed[0].output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT, observed[0].delivery)
        assertNull(observed[0].errorMessage)
    }

    @Test
    fun toolResultIsFedBackWhenReturnDirectDisabled() {
        val callId = "indirect_call"
        val toolName = "indirectEcho"
        val invocationCount = AtomicInteger(0)
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val response = mockk<Response>(relaxed = true)
        every { response.id() } returns "resp-tool"
        every { response.conversation() } returns Optional.empty()
        val tool = ToolCallback(
            name = toolName,
            description = "간접 반환",
            inputType = ToolArgs::class,
        ) { args ->
            invocationCount.incrementAndGet()
            Mono.just(mapOf("echo" to args.value.uppercase()))
        }

        val toolCallStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(response)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(functionCallOutputItem(callId, toolName, "{\"value\":\"seoul\"}"))
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            )
        )

        val finalMessageId = "msg_after_tool"
        val messageItem = messageOutputItem(finalMessageId)
        val assistantStream = fakeStream(
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(finalMessageId)
                    .delta("간접 응답")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .logprobs(emptyList())
                    .build()
            ),
            ResponseStreamEvent.ofOutputTextDone(
                ResponseTextDoneEvent.builder()
                    .itemId(finalMessageId)
                    .contentIndex(0)
                    .text("간접 응답")
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = finalMessageId,
                text = "간접 응답",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
        )

        val client = clientWithStreams(
            tools = listOf(tool),
            recordedRequests = recordedRequests,
            streams = listOf(toolCallStream, assistantStream)
        )

        val outputs = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "indirect?"))
            )
        )

        val emitted = outputs.collectList().block().orEmpty()
        assertTrue(emitted.isNotEmpty())
        // B안: 첫 라운드 ResponseCreated(resp-tool) 만 노출. 두 번째 라운드는 ResponseCreated/Completed 가 없음 (fake stream).
        assertEquals(
            listOf(
                "response.created",
                "response.function_call.done",
                "response.message.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
            ),
            emitted.map { it.type },
            "types=${emitted.map { it.type }}",
        )
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals("{\"value\":\"seoul\"}", observed[0].argumentsJson)
        assertEquals("{\"echo\":\"SEOUL\"}", observed[0].output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL, observed[0].delivery)
        assertNull(observed[0].errorMessage)

        assertEquals(1, invocationCount.get(), "tool should be invoked once")
        assertEquals(2, recordedRequests.size)
        assertEquals("resp-tool", recordedRequests[1].previousResponseId().getOrNull())
    }

    @Test
    fun toolCallOnlyStepLifecycleEventsAreNotEmitted() {
        val callId = "hidden_tool_call"
        val toolName = "indirectEcho"
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val toolResponse = responseWithUsage("resp-tool")
        val finalResponse = responseWithUsage("resp-final")
        val tool = ToolCallback(
            name = toolName,
            description = "간접 반환",
            inputType = ToolArgs::class,
        ) { args ->
            Mono.just(mapOf("echo" to args.value.uppercase()))
        }

        val toolCallStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(toolResponse)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(functionCallOutputItem(callId, toolName, "{\"value\":\"seoul\"}"))
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            ),
            completedEvent(
                response = toolResponse,
                sequence = 2,
            ),
        )

        val finalMessageId = "msg_after_hidden_tool"
        val messageItem = messageOutputItem(finalMessageId)
        val assistantStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(finalResponse)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(finalMessageId)
                    .delta("간접 응답")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = finalMessageId,
                text = "간접 응답",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
            completedEvent(
                response = finalResponse,
                sequence = 5,
            ),
        )

        val client = clientWithStreams(
            tools = listOf(tool),
            recordedRequests = recordedRequests,
            streams = listOf(toolCallStream, assistantStream)
        )

        val emitted = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "indirect?"))
            )
        ).collectList().block().orEmpty()

        // B안: 첫 라운드 ResponseCreated(resp-tool) 만 노출, 마지막 ResponseCompleted 만 노출(토큰은 합산).
        assertEquals(
            listOf(
                "response.created",
                "response.function_call.done",
                "response.message.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            emitted.map { it.type },
            "types=${emitted.map { it.type }}",
        )
        assertEquals(
            listOf("resp-tool"),
            emitted.filterIsInstance<LlmStreamEvent.ResponseCreated>().map { it.id }
        )
        val completed = emitted.filterIsInstance<LlmStreamEvent.ResponseCompleted>().single()
        // resp-tool 과 resp-final 모두 1/1/2 토큰 → 합산 2/2/4
        assertEquals(2L, completed.inputTokens)
        assertEquals(2L, completed.outputTokens)
        assertEquals(4L, completed.totalTokens)
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals(callId, observed[0].callId)
        assertNotNull(observed[0].output)
        assertEquals("resp-tool", recordedRequests[1].previousResponseId().getOrNull())
    }

    @Test
    fun textThenFunctionCallThenTextPreservesMessageIndexesAcrossSteps() {
        val callId = "between_messages_call"
        val toolName = "indirectEcho"
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val firstResponse = responseWithUsage("resp-1")
        val secondResponse = responseWithUsage("resp-2")
        val firstMessageId = "msg_before_tool"
        val secondMessageId = "msg_after_tool"
        val firstMessageItem = messageOutputItem(firstMessageId)
        val secondMessageItem = messageOutputItem(secondMessageId)
        val functionCallItem = functionCallOutputItem(callId, toolName, "{\"value\":\"seoul\"}")
        val tool = ToolCallback(
            name = toolName,
            description = "간접 반환",
            inputType = ToolArgs::class,
        ) { args ->
            Mono.just(mapOf("echo" to args.value.uppercase()))
        }

        val toolCallStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(firstResponse)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(firstMessageItem)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(firstMessageId)
                    .delta("before")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = firstMessageId,
                text = "before",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(firstMessageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(functionCallItem)
                    .outputIndex(1)
                    .sequenceNumber(5)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(functionCallItem)
                    .outputIndex(1)
                    .sequenceNumber(6)
                    .build()
            ),
            completedEvent(
                response = firstResponse,
                sequence = 7,
            ),
        )
        val assistantStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(secondResponse)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(secondMessageItem)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(secondMessageId)
                    .delta("after")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = secondMessageId,
                text = "after",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(secondMessageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
            completedEvent(
                response = secondResponse,
                sequence = 5,
            ),
        )

        val client = clientWithStreams(
            tools = listOf(tool),
            recordedRequests = recordedRequests,
            streams = listOf(toolCallStream, assistantStream),
        )

        val emitted = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "go"))
            )
        ).collectList().block().orEmpty()

        val types = emitted.map { it.type }
        assertEquals(
            listOf(
                "response.created",
                "response.message.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.function_call.done",
                "response.message.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            types,
            "types=$types",
        )
        val addedIndexes = emitted.filterIsInstance<LlmStreamEvent.ResponseMessageAdded>().map { it.itemIndex }
        assertEquals(listOf(0L, 1L), addedIndexes)
        val deltas = emitted.filterIsInstance<LlmStreamEvent.MessageDelta>().map { it.delta }
        assertEquals(listOf("before", "after"), deltas)
        assertEquals(2, recordedRequests.size)
        assertEquals("resp-1", recordedRequests[1].previousResponseId().getOrNull())
    }

    @Test
    fun toolResultIsFedBackWhenInitialRequestUsesInstructionsOnly() {
        val callId = "indirect_call_instructions_only"
        val toolName = "indirectEcho"
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val response = mockk<Response>(relaxed = true)
        every { response.id() } returns "resp-tool"
        every { response.conversation() } returns Optional.empty()
        val tool = ToolCallback(
            name = toolName,
            description = "간접 반환",
            inputType = ToolArgs::class,
        ) { args ->
            Mono.just(mapOf("echo" to args.value.uppercase()))
        }

        val toolCallStream = fakeStream(
            ResponseStreamEvent.ofCreated(
                ResponseCreatedEvent.builder()
                    .response(response)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(functionCallOutputItem(callId, toolName, "{\"value\":\"seoul\"}"))
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .build()
            )
        )

        val finalMessageId = "msg_after_tool_instructions_only"
        val messageItem = messageOutputItem(finalMessageId)
        val assistantStream = fakeStream(
            ResponseStreamEvent.Companion.ofOutputItemAdded(
                ResponseOutputItemAddedEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(0)
                    .build()
            ),
            ResponseStreamEvent.Companion.ofOutputTextDelta(
                ResponseTextDeltaEvent.builder()
                    .itemId(finalMessageId)
                    .delta("간접 응답")
                    .contentIndex(0)
                    .outputIndex(0)
                    .sequenceNumber(1)
                    .logprobs(emptyList())
                    .build()
            ),
            ResponseStreamEvent.ofOutputTextDone(
                ResponseTextDoneEvent.builder()
                    .itemId(finalMessageId)
                    .contentIndex(0)
                    .text("간접 응답")
                    .outputIndex(0)
                    .sequenceNumber(2)
                    .logprobs(emptyList())
                    .build()
            ),
            contentPartDoneEvent(
                itemId = finalMessageId,
                text = "간접 응답",
                contentIndex = 0,
                outputIndex = 0,
                sequence = 3,
            ),
            ResponseStreamEvent.Companion.ofOutputItemDone(
                ResponseOutputItemDoneEvent.builder()
                    .item(messageItem)
                    .outputIndex(0)
                    .sequenceNumber(4)
                    .build()
            ),
        )

        val client = clientWithStreams(
            tools = listOf(tool),
            recordedRequests = recordedRequests,
            streams = listOf(toolCallStream, assistantStream)
        )

        val emitted = client.stream(LlmStreamRequest()).collectList().block().orEmpty()

        assertTrue(emitted.isNotEmpty())
        assertEquals(2, recordedRequests.size)
        assertNull(recordedRequests[0].input().getOrNull())
        assertEquals("단위 테스트", recordedRequests[0].instructions().getOrNull())
        assertEquals("resp-tool", recordedRequests[1].previousResponseId().getOrNull())
        assertTrue(recordedRequests[1].instructions().getOrNull()?.isNotBlank() == true)

        val secondInputs = recordedRequests[1].input().orElseThrow().asResponse()
        assertEquals(1, secondInputs.size)
        assertTrue(secondInputs.single().isFunctionCallOutput())
        val functionOutput = secondInputs.single().functionCallOutput().orElseThrow()
        assertEquals(callId, functionOutput.callId())
        assertEquals("{\"echo\":\"SEOUL\"}", functionOutput.output().asString())
    }

    @Test
    fun commonOptionsAreMappedToOpenAiResponseRequest() {
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val client = clientWithStreams(
            recordedRequests = recordedRequests,
            streams = listOf(fakeStream())
        )

        client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "options")),
                options = LlmStreamOptions(
                    temperature = 0.2,
                    topP = 0.9,
                    includeThoughts = true,
                    maxTokens = 128,
                    textFormat = "json_object",
                    effort = ReasoningEffortType.HIGH,
                    verbosity = VerbosityType.LOW,
                    summary = SummaryType.DETAILED,
                    store = false,
                ),
            )
        ).collectList().block()

        assertEquals(1, recordedRequests.size)
        val sent = recordedRequests.single()
        assertEquals(0.2, sent.temperature().getOrNull())
        assertEquals(0.9, sent.topP().getOrNull())
        assertEquals(128L, sent.maxOutputTokens().getOrNull())
        assertEquals(false, sent.store().getOrNull())
        assertEquals(
            listOf(ResponseIncludable.REASONING_ENCRYPTED_CONTENT),
            sent.include().getOrNull(),
        )

        val reasoning = sent.reasoning().getOrNull()
        assertNotNull(reasoning)
        assertEquals("high", reasoning.effort().getOrNull()?.asString())
        assertEquals("detailed", reasoning.summary().getOrNull()?.asString())

        val text = sent.text().getOrNull()
        assertNotNull(text)
        assertTrue(text.format().getOrNull()?.isJsonObject() == true)
        assertEquals("low", text.verbosity().getOrNull()?.asString())
    }

    @Test
    fun fileSearchToolIsIncludedInOpenAiRequest() {
        val recordedRequests = mutableListOf<ResponseCreateParams>()
        val client = clientWithStreams(
            tools = listOf(
                LlmFileSearchTool(
                    vectorStoreIds = listOf("vs_123"),
                    maxNumResults = 5,
                )
            ),
            recordedRequests = recordedRequests,
            streams = listOf(fakeStream())
        )

        client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "search files"))
            )
        ).collectList().block()

        assertEquals(1, recordedRequests.size)
        val tools = recordedRequests.single().tools().getOrNull().orEmpty()
        assertEquals(1, tools.size)
        assertTrue(tools.single().isFileSearch())
        val fileSearch = tools.single().asFileSearch()
        assertEquals(listOf("vs_123"), fileSearch.vectorStoreIds())
        assertEquals(5L, fileSearch.maxNumResults().getOrNull())
    }

}
