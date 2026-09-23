package malibu.llm.streamclient

import reactor.core.publisher.FluxSink

internal class BufferedStepEventEmitter(
    private val sink: FluxSink<LlmStreamEvent>,
) {
    private val bufferedLifecycleEvents = mutableListOf<LlmStreamEvent>()
    private var emittedExternalEvent = false

    fun emitLifecycle(event: LlmStreamEvent) {
        if (sink.isCancelled) {
            return
        }
        if (emittedExternalEvent) {
            sink.next(event)
        } else {
            bufferedLifecycleEvents += event
        }
    }

    fun emitExternal(event: LlmStreamEvent) {
        if (sink.isCancelled) {
            return
        }
        flushLifecycleEvents()
        emittedExternalEvent = true
        sink.next(event)
    }

    fun flushLifecycleEvents() {
        if (sink.isCancelled || bufferedLifecycleEvents.isEmpty()) {
            return
        }
        bufferedLifecycleEvents.forEach(sink::next)
        bufferedLifecycleEvents.clear()
    }

    fun discardLifecycleEvents() {
        bufferedLifecycleEvents.clear()
    }
}
