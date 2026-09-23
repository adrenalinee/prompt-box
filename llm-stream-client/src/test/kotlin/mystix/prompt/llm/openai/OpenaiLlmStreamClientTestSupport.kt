package mystix.prompt.llm.openai

import com.openai.client.OpenAIClientAsync
import com.openai.core.JsonValue
import com.openai.core.http.AsyncStreamResponse
import com.openai.models.responses.*
import com.openai.services.async.ResponseServiceAsync
import io.mockk.every
import io.mockk.mockk
import malibu.llm.streamclient.LlmToolSpec
import malibu.llm.streamclient.LlmStreamRequest
import malibu.llm.streamclient.openai.OpenaiLlmStreamClient
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

data class ToolArgs(val value: String = "")

private val jsonMapper = jsonMapper {
    addModule(KotlinModule.Builder().build())
}

fun messageOutputItem(id: String): ResponseOutputItem {
    val textContent = ResponseOutputText.builder()
        .text("")
        .annotations(emptyList<ResponseOutputText.Annotation>())
        .logprobs(emptyList<ResponseOutputText.Logprob>())
        .build()

    val message = ResponseOutputMessage.builder()
        .id(id)
        .status(ResponseOutputMessage.Status.COMPLETED)
        .role(JsonValue.from("assistant"))
        .addContent(textContent)
        .build()

    return ResponseOutputItem.ofMessage(message)
}

fun functionCallOutputItem(
    callId: String,
    toolName: String,
    argsJson: String,
): ResponseOutputItem {
    val functionCall = ResponseFunctionToolCall.builder()
        .callId(callId)
        .name(toolName)
        .arguments(argsJson)
        .build()
    return ResponseOutputItem.ofFunctionCall(functionCall)
}

fun clientWithStreams(
    tools: List<LlmToolSpec> = emptyList(),
    defaultRequestSpec: LlmStreamRequest = LlmStreamRequest(
        instructions = "단위 테스트",
        tools = tools,
    ),
    recordedRequests: MutableList<ResponseCreateParams>? = null,
    streams: List<AsyncStreamResponse<ResponseStreamEvent>>,
): OpenaiLlmStreamClient {
    require(streams.isNotEmpty()) { "At least one fake stream is required" }
    val responseService = mockk<ResponseServiceAsync>()
    val iterator = streams.iterator()
    every { responseService.createStreaming(any<ResponseCreateParams>()) } answers {
        recordedRequests?.add(firstArg())
        require(iterator.hasNext()) { "No fake stream remaining" }
        iterator.next()
    }
    val openAiClient = mockk<OpenAIClientAsync>()
    every { openAiClient.responses() } returns responseService
    return OpenaiLlmStreamClient(
        openAIClient = openAiClient,
        defaultRequestSpec = defaultRequestSpec,
        jsonMapper = jsonMapper,
    )
}

fun fakeStream(vararg events: ResponseStreamEvent): FakeAsyncStreamResponse =
    FakeAsyncStreamResponse(events.toList())

fun contentPartDoneEvent(
    itemId: String,
    text: String,
    contentIndex: Long,
    outputIndex: Long,
    sequence: Long,
): ResponseStreamEvent {
    val outputText = ResponseOutputText.builder()
        .text(text)
        .annotations(emptyList<ResponseOutputText.Annotation>())
        .logprobs(emptyList<ResponseOutputText.Logprob>())
        .build()
    val part = ResponseContentPartDoneEvent.Part.ofOutputText(outputText)
    val event = ResponseContentPartDoneEvent.builder()
        .itemId(itemId)
        .contentIndex(contentIndex)
        .outputIndex(outputIndex)
        .sequenceNumber(sequence)
        .part(part)
        .build()
    return ResponseStreamEvent.ofContentPartDone(event)
}

fun responseWithUsage(id: String): Response {
    val response = mockk<Response>(relaxed = true)
    val usage = mockk<ResponseUsage>(relaxed = true)
    val inputDetails = mockk<ResponseUsage.InputTokensDetails>(relaxed = true)
    val outputDetails = mockk<ResponseUsage.OutputTokensDetails>(relaxed = true)
    every { response.id() } returns id
    every { response.conversation() } returns Optional.empty()
    every { response.usage() } returns Optional.of(usage)
    every { usage.inputTokens() } returns 1
    every { usage.inputTokensDetails() } returns inputDetails
    every { inputDetails.cachedTokens() } returns 0
    every { usage.outputTokens() } returns 1
    every { usage.outputTokensDetails() } returns outputDetails
    every { outputDetails.reasoningTokens() } returns 0
    every { usage.totalTokens() } returns 2
    return response
}

fun completedEvent(
    response: Response,
    sequence: Long,
): ResponseStreamEvent {
    return ResponseStreamEvent.ofCompleted(
        ResponseCompletedEvent.builder()
            .response(response)
            .sequenceNumber(sequence)
            .build()
    )
}

class FakeAsyncStreamResponse(
    private val events: List<ResponseStreamEvent>,
) : AsyncStreamResponse<ResponseStreamEvent> {

    private val completion = CompletableFuture<Void?>()

    override fun subscribe(
        handler: AsyncStreamResponse.Handler<in ResponseStreamEvent>
    ): AsyncStreamResponse<ResponseStreamEvent> {
        emit(handler)
        return this
    }

    override fun subscribe(
        handler: AsyncStreamResponse.Handler<in ResponseStreamEvent>,
        executor: Executor,
    ): AsyncStreamResponse<ResponseStreamEvent> {
        executor.execute { emit(handler) }
        return this
    }

    private fun emit(handler: AsyncStreamResponse.Handler<in ResponseStreamEvent>) {
        events.forEach { handler.onNext(it) }
        handler.onComplete(Optional.empty())
        completion.complete(null)
    }

    override fun onCompleteFuture(): CompletableFuture<Void?> = completion

    override fun close() {
        completion.complete(null)
    }
}
