package mystix.prompt.llm

import mystix.prompt.llm.exception.InputEmptyException
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 실제 벤더 API 호출 없이 미리 준비한 메시지를 순서대로 스트리밍하는 테스트용 구현체.
 *
 * [stream] 이 호출될 때마다 생성자에서 받은 응답 큐에서 다음 메시지 하나를 꺼내
 * 공통 SSE 이벤트 시퀀스로 방출한다.
 */
class FakeLlmStreamClient(
    responses: Collection<String>,
    override val vendorName: String = "fake",
) : LlmStreamClient {
    private val queuedResponses = ConcurrentLinkedQueue(responses)
    private val responseCounter = AtomicLong(0L)
    private val recordedRequests = CopyOnWriteArrayList<LlmStreamRequest>()

    val receivedRequests: List<LlmStreamRequest>
        get() = recordedRequests.toList()

    override fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent> {
        return Flux.defer {
            validateRequest(requestSpec)
            recordedRequests += requestSpec

            val responseText = queuedResponses.poll()
                ?: return@defer Flux.error(
                    IllegalStateException("No fake LLM responses remaining for vendor=$vendorName")
                )

            Flux.fromIterable(createEvents(responseText, requestSpec.modelKey))
        }
    }

    private fun validateRequest(requestSpec: LlmStreamRequest) {
        if (requestSpec.instructions.isNullOrBlank() && requestSpec.inputs.isNullOrEmpty()) {
            throw InputEmptyException()
        }
    }

    private fun createEvents(
        responseText: String,
        model: String?,
    ): List<LlmStreamEvent> {
        val responseNumber = responseCounter.incrementAndGet()
        val responseId = "fake-response-$responseNumber"
        val itemId = "fake-message-$responseNumber"
        val sequence = AtomicLong(0L)

        return buildList {
            add(
                LlmStreamEvent.ResponseCreated(
                    id = responseId,
                    sequence = sequence.getAndIncrement(),
                    model = model,
                )
            )
            add(
                LlmStreamEvent.ResponseMessageAdded(
                    itemId = itemId,
                    sequence = sequence.getAndIncrement(),
                    itemIndex = 0L,
                )
            )
            add(
                LlmStreamEvent.MessageContentPartAdded(
                    itemId = itemId,
                    sequence = sequence.getAndIncrement(),
                    itemIndex = 0L,
                    partIndex = 0L,
                )
            )

            if (responseText.isNotEmpty()) {
                add(
                    LlmStreamEvent.MessageDelta(
                        itemId = itemId,
                        sequence = sequence.getAndIncrement(),
                        itemIndex = 0L,
                        partIndex = 0L,
                        delta = responseText,
                    )
                )
            }

            add(
                LlmStreamEvent.MessageContentPartDone(
                    itemId = itemId,
                    sequence = sequence.getAndIncrement(),
                    itemIndex = 0L,
                    partIndex = 0L,
                    text = responseText,
                )
            )
            add(
                LlmStreamEvent.ResponseMessageDone(
                    itemId = itemId,
                    sequence = sequence.getAndIncrement(),
                    itemIndex = 0L,
                )
            )
            add(
                LlmStreamEvent.ResponseCompleted(
                    id = responseId,
                    sequence = sequence.getAndIncrement(),
                    inputTokens = null,
                    cachedTokens = null,
                    outputTokens = null,
                    reasoningTokens = null,
                    totalTokens = null,
                )
            )
        }
    }
}
