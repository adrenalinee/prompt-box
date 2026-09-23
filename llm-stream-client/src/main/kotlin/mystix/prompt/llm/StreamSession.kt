package mystix.prompt.llm

import reactor.core.publisher.FluxSink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * stream() 한 호출 동안 살아 있고, 모든 라운드(초기 + tool follow-up)를 가로지르는 emission session.
 *
 * 컨슈머에게는 모든 라운드가 하나의 응답처럼 보여야 한다.
 *   - 첫 라운드의 ResponseCreated 만 외부에 노출. (외부 이벤트가 처음 발행될 때 flush)
 *   - 중간 라운드의 ResponseCompleted: 토큰만 누적하고 이벤트는 폐기.
 *   - 마지막 라운드의 ResponseCompleted: 누적 토큰을 실어 한 번만 외부에 노출.
 *   - itemIndex / sequence: 라운드 경계를 넘어 단조 증가.
 *
 * BufferedStepEventEmitter 의 라운드 내 lifecycle 버퍼링 역할도 이 세션이 대신한다 —
 * 다만 라운드 경계를 가로지른다는 점이 다르다.
 */
internal class StreamSession(
    private val sink: FluxSink<LlmStreamEvent>,
) {
    val nextItemIndex = AtomicLong(0L)
    val nextSequence = AtomicLong(0L)
    val accumulatedUsage = UsageAccumulator()

    private val firstResponseCreated = AtomicReference<LlmStreamEvent.ResponseCreated?>(null)
    private val responseCreatedFlushed = AtomicBoolean(false)

    val isCancelled: Boolean get() = sink.isCancelled

    /**
     * 첫 ResponseCreated 만 기억. 이후 라운드의 ResponseCreated 는 무시된다.
     * 실제 외부 방출은 첫 외부 이벤트 직전에 일어남 (flushResponseCreatedIfNeeded).
     */
    fun bufferResponseCreated(event: LlmStreamEvent.ResponseCreated) {
        firstResponseCreated.compareAndSet(null, event)
    }

    /**
     * 외부 이벤트(메시지/추론/function call 등)를 컨슈머에게 방출.
     * 첫 호출에서 buffered ResponseCreated 를 먼저 flush.
     */
    fun emitExternal(event: LlmStreamEvent) {
        if (sink.isCancelled) return
        flushResponseCreatedIfNeeded()
        sink.next(event)
    }

    private fun flushResponseCreatedIfNeeded() {
        if (responseCreatedFlushed.compareAndSet(false, true)) {
            firstResponseCreated.get()?.let { sink.next(it) }
        }
    }

    /**
     * 중간 라운드(다음 라운드가 이어질) 의 ResponseCompleted 를 흡수.
     * 토큰만 합산되고 이벤트는 방출되지 않는다.
     */
    fun absorbRoundCompleted(roundCompleted: LlmStreamEvent.ResponseCompleted?) {
        accumulatedUsage.add(roundCompleted)
    }

    /**
     * 마지막 라운드에서 호출. 누적된 토큰을 실은 ResponseCompleted 를 방출 후 sink.complete().
     */
    fun finalize(roundCompleted: LlmStreamEvent.ResponseCompleted?) {
        if (sink.isCancelled) return
        accumulatedUsage.add(roundCompleted)
        flushResponseCreatedIfNeeded()

        if (accumulatedUsage.hasAnyValue() || roundCompleted != null) {
            val finalCompleted = LlmStreamEvent.ResponseCompleted(
                id = roundCompleted?.id ?: firstResponseCreated.get()?.id ?: "",
                sequence = roundCompleted?.sequence ?: nextSequence.getAndIncrement(),
                inputTokens = accumulatedUsage.inputTokens,
                cachedTokens = accumulatedUsage.cachedTokens,
                outputTokens = accumulatedUsage.outputTokens,
                reasoningTokens = accumulatedUsage.reasoningTokens,
                totalTokens = accumulatedUsage.totalTokens,
            )
            sink.next(finalCompleted)
        }
        sink.complete()
    }

    /**
     * RETURN_DIRECT 경로: synthetic 메시지는 이미 방출됨. ResponseCompleted 없이 종료.
     */
    fun finalizeSilently() {
        if (sink.isCancelled) return
        flushResponseCreatedIfNeeded()
        sink.complete()
    }

    fun error(t: Throwable) {
        if (!sink.isCancelled) sink.error(t)
    }
}

/**
 * 라운드별 ResponseCompleted 의 토큰을 누적.
 *
 * 합산 규칙: 한 라운드의 값이 null 이면 무시. 양쪽이 모두 값이 있으면 더함.
 * 의미: "이번 LLM 호출 체인에서 사용된 총 토큰". 벤더별 input 의미 차이(중복 시스템 프롬프트 등)는
 * 단순 합산으로 처리한다 — 컨슈머는 한 호출의 비용으로 해석.
 */
internal class UsageAccumulator {
    var inputTokens: Long? = null
        private set
    var cachedTokens: Long? = null
        private set
    var outputTokens: Long? = null
        private set
    var reasoningTokens: Long? = null
        private set
    var totalTokens: Long? = null
        private set

    fun add(round: LlmStreamEvent.ResponseCompleted?) {
        if (round == null) return
        inputTokens = sum(inputTokens, round.inputTokens)
        cachedTokens = sum(cachedTokens, round.cachedTokens)
        outputTokens = sum(outputTokens, round.outputTokens)
        reasoningTokens = sum(reasoningTokens, round.reasoningTokens)
        totalTokens = sum(totalTokens, round.totalTokens)
    }

    fun hasAnyValue(): Boolean =
        inputTokens != null || cachedTokens != null || outputTokens != null ||
            reasoningTokens != null || totalTokens != null

    private fun sum(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }
}
