package mystix.prompt.llm.xai

import mystix.prompt.llm.LlmMessageInputItem
import mystix.prompt.llm.LlmFileSearchTool
import mystix.prompt.llm.LlmStreamOptions
import mystix.prompt.llm.LlmStreamEvent
import mystix.prompt.llm.LlmStreamRequest
import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.ToolCallback
import mystix.prompt.llm.exception.InputEmptyException
import mystix.prompt.llm.xai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

class XaiLlmStreamClientTest {
    private val jsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }

    @Test
    fun `streams text chunks into common events`() {
        val rawClient = RecordingXaiStreamClient(
            mutableListOf(
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","sequence_number":0,"response":{"id":"resp-1","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.added",
                        payload = """{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"m1","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.added",
                        payload = """{"type":"response.content_part.added","sequence_number":2,"item_id":"m1","output_index":0,"content_index":0,"part":{"type":"output_text"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_text.delta",
                        payload = """{"type":"response.output_text.delta","sequence_number":3,"item_id":"m1","output_index":0,"content_index":0,"delta":"hel"}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_text.delta",
                        payload = """{"type":"response.output_text.delta","sequence_number":4,"item_id":"m1","output_index":0,"content_index":0,"delta":"lo"}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.done",
                        payload = """{"type":"response.content_part.done","sequence_number":5,"item_id":"m1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"hello"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","sequence_number":6,"output_index":0,"item":{"id":"m1","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.completed",
                        payload = """{"type":"response.completed","sequence_number":7,"response":{"id":"resp-1","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""",
                    ),
                )
            )
        )

        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "hi", role = LlmMessageInputItem.Role.USER)),
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

        val done = emitted.filterIsInstance<LlmStreamEvent.MessageContentPartDone>().single()
        assertEquals("hello", done.text)

        val sentRequest = rawClient.requests.first()
        assertEquals("grok-4-1-fast-non-reasoning", sentRequest.model)
        assertEquals("sys", sentRequest.instructions)
        assertEquals(1, sentRequest.input.size)
    }

    @Test
    fun `tool non-direct triggers second stream step with function response context`() {
        val rawClient = RecordingXaiStreamClient(
            mutableListOf(
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","response":{"id":"resp-tool","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":0,"item":{"id":"fc-1","call_id":"call-1","type":"function_call","name":"lookup","arguments":"{\"query\":\"seoul\"}"}}""",
                    ),
                ),
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","response":{"id":"resp-final","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.added",
                        payload = """{"type":"response.output_item.added","output_index":0,"item":{"id":"m2","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.added",
                        payload = """{"type":"response.content_part.added","item_id":"m2","output_index":0,"content_index":0,"part":{"type":"output_text"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_text.delta",
                        payload = """{"type":"response.output_text.delta","item_id":"m2","output_index":0,"content_index":0,"delta":"done"}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.done",
                        payload = """{"type":"response.content_part.done","item_id":"m2","output_index":0,"content_index":0,"part":{"type":"output_text","text":"done"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":0,"item":{"id":"m2","type":"message"}}""",
                    ),
                ),
            )
        )

        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("result" to args.query.uppercase())) }

        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go", role = LlmMessageInputItem.Role.USER)),
            )
        ).collectList().block().orEmpty()

        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>()
        assertEquals(1, observed.size)
        assertEquals("""{"query":"seoul"}""", observed.single().argumentsJson)
        assertEquals("""{"result":"SEOUL"}""", observed.single().output)
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL, observed.single().delivery)
        // B안: 첫 라운드의 ResponseCreated 만 컨슈머에게 노출.
        assertEquals(
            listOf("resp-tool"),
            emitted.filterIsInstance<LlmStreamEvent.ResponseCreated>().map { it.id }
        )
        val finalDelta = emitted.filterIsInstance<LlmStreamEvent.MessageDelta>().lastOrNull()
        assertEquals("done", finalDelta?.delta)

        assertEquals(2, rawClient.requests.size)
        val secondRequest = rawClient.requests[1]
        assertEquals("resp-tool", secondRequest.previousResponseId)
        assertEquals(1, secondRequest.input.size)
        val functionOutput = secondRequest.input.first() as XaiInputItem.FunctionCallOutput
        assertEquals("call-1", functionOutput.callId)
        assertTrue(functionOutput.output.contains("SEOUL"))
    }

    @Test
    fun `text-A then function call then text-B yields inline ordering with single response lifecycle`() {
        val rawClient = RecordingXaiStreamClient(
            mutableListOf(
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","response":{"id":"resp-1","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.added",
                        payload = """{"type":"response.output_item.added","output_index":0,"item":{"id":"m1","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.added",
                        payload = """{"type":"response.content_part.added","item_id":"m1","output_index":0,"content_index":0,"part":{"type":"output_text"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_text.delta",
                        payload = """{"type":"response.output_text.delta","item_id":"m1","output_index":0,"content_index":0,"delta":"A..."}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.done",
                        payload = """{"type":"response.content_part.done","item_id":"m1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"A..."}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":0,"item":{"id":"m1","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":1,"item":{"id":"fc-1","call_id":"call-1","type":"function_call","name":"lookup","arguments":"{\"query\":\"x\"}"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.completed",
                        payload = """{"type":"response.completed","response":{"id":"resp-1","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""",
                    ),
                ),
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","response":{"id":"resp-2","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.added",
                        payload = """{"type":"response.output_item.added","output_index":0,"item":{"id":"m2","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.added",
                        payload = """{"type":"response.content_part.added","item_id":"m2","output_index":0,"content_index":0,"part":{"type":"output_text"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_text.delta",
                        payload = """{"type":"response.output_text.delta","item_id":"m2","output_index":0,"content_index":0,"delta":"B..."}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.content_part.done",
                        payload = """{"type":"response.content_part.done","item_id":"m2","output_index":0,"content_index":0,"part":{"type":"output_text","text":"B..."}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":0,"item":{"id":"m2","type":"message"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.completed",
                        payload = """{"type":"response.completed","response":{"id":"resp-2","usage":{"input_tokens":15,"output_tokens":4,"total_tokens":19}}}""",
                    ),
                ),
            )
        )

        val tool = ToolCallback(
            name = "lookup",
            description = "",
            inputType = LookupArgs::class,
        ) { args -> Mono.just(mapOf("r" to args.query)) }

        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go", role = LlmMessageInputItem.Role.USER)),
            )
        ).collectList().block().orEmpty()

        val types = emitted.map { it.type }
        // text-A 클러스터 → function_call.done → text-B 클러스터 → ResponseCompleted
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

        // ResponseCreated 는 첫 라운드 ID 하나만.
        val createdIds = emitted.filterIsInstance<LlmStreamEvent.ResponseCreated>().map { it.id }
        assertEquals(listOf("resp-1"), createdIds)

        // ResponseCompleted 는 한 번만, 토큰은 두 라운드 합산.
        val completed = emitted.filterIsInstance<LlmStreamEvent.ResponseCompleted>().single()
        assertEquals(25L, completed.inputTokens)
        assertEquals(6L, completed.outputTokens)
        assertEquals(31L, completed.totalTokens)

        // itemIndex 가 라운드 경계를 넘어 단조 증가. (function call 은 itemIndex 슬롯을 소비하지 않음)
        val itemAddedIndexes = emitted.filterIsInstance<LlmStreamEvent.ResponseMessageAdded>().map { it.itemIndex }
        assertEquals(listOf(0L, 1L), itemAddedIndexes)
    }

    @Test
    fun `return-direct tool emits wrapped synthetic message and terminates`() {
        val rawClient = RecordingXaiStreamClient(
            mutableListOf(
                Flux.just(
                    XaiRawStreamEvent(
                        type = "response.created",
                        payload = """{"type":"response.created","response":{"id":"resp-d","model":"grok-2"}}""",
                    ),
                    XaiRawStreamEvent(
                        type = "response.output_item.done",
                        payload = """{"type":"response.output_item.done","output_index":0,"item":{"id":"fc-d","call_id":"call-d","type":"function_call","name":"direct","arguments":"{\"query\":\"x\"}"}}""",
                    ),
                )
            )
        )
        val tool = ToolCallback(
            name = "direct",
            description = "",
            inputType = LookupArgs::class,
            returnDirect = true,
        ) { args -> Mono.just("hello-${args.query}") }
        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(tools = listOf(tool)), jsonMapper)
        val emitted = client.stream(
            LlmStreamRequest(
                instructions = "sys",
                inputs = listOf(LlmMessageInputItem(content = "go", role = LlmMessageInputItem.Role.USER)),
            )
        ).collectList().block().orEmpty()

        val types = emitted.map { it.type }
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
            types,
            "types=$types",
        )
        // 두 번째 라운드 호출은 없어야 함.
        assertEquals(1, rawClient.requests.size)
        val observed = emitted.filterIsInstance<LlmStreamEvent.FunctionCallObserved>().single()
        assertEquals(LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT, observed.delivery)
        assertEquals("hello-x", observed.output)
    }

    @Test
    fun `fails when request has no input even if instructions are present`() {
        val rawClient = RecordingXaiStreamClient(mutableListOf())
        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)

        StepVerifier.create(client.stream(LlmStreamRequest(instructions = "sys")))
            .expectErrorSatisfies { error ->
                assertTrue(error is InputEmptyException)
            }
            .verify()

        assertTrue(rawClient.requests.isEmpty())
    }

    @Test
    fun `maps xai-supported common options and file search tool to raw request`() {
        val rawClient = RecordingXaiStreamClient(mutableListOf(Flux.empty()))
        val client = XaiLlmStreamClient(rawClient, LlmStreamRequest(), jsonMapper)

        client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "hi")),
                options = LlmStreamOptions(
                    includeThoughts = true,
                    textFormat = "json_object",
                    effort = ReasoningEffortType.HIGH,
                    summary = SummaryType.DETAILED,
                    store = false,
                ),
                tools = listOf(
                    LlmFileSearchTool(
                        vectorStoreIds = listOf("vs_123"),
                        maxNumResults = 3,
                    )
                ),
            )
        ).collectList().block()

        val sent = rawClient.requests.single()
        assertTrue(sent.store == false)
        assertEquals(listOf("reasoning.encrypted_content"), sent.include)
        assertEquals("high", sent.reasoning?.effort)
        assertEquals("detailed", sent.reasoning?.summary)
        assertEquals("json_object", sent.text?.format?.get("type"))
        val fileSearchTool = sent.tools.single() as XaiFileSearchTool
        assertEquals(listOf("vs_123"), fileSearchTool.vectorStoreIds)
        assertEquals(3L, fileSearchTool.maxNumResults)
    }

    data class LookupArgs(
        val query: String,
    )

    private class RecordingXaiStreamClient(
        private val queuedResponses: MutableList<Flux<XaiRawStreamEvent>>,
    ) : XaiResponsesStreamClient {
        val requests = mutableListOf<XaiResponsesStreamRequest>()

        override fun stream(request: XaiResponsesStreamRequest): Flux<XaiRawStreamEvent> {
            requests.add(request)
            return if (queuedResponses.isEmpty()) {
                Flux.empty()
            } else {
                queuedResponses.removeAt(0)
            }
        }
    }
}
