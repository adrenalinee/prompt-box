package mystix.prompt.llm

import reactor.core.publisher.Flux

internal const val DEFAULT_MAX_BUFFERED_EVENTS = 1024

internal fun effectiveMaxBufferedEvents(
    overrideOptions: LlmStreamOptions?,
    baseOptions: LlmStreamOptions?,
): Int {
    return sequenceOf(overrideOptions, baseOptions)
        .mapNotNull { options -> options?.maxBufferedEvents }
        .firstOrNull { maxBufferedEvents -> maxBufferedEvents > 0 }
        ?: DEFAULT_MAX_BUFFERED_EVENTS
}

internal fun <T : Any> Flux<T>.limitBufferedEvents(maxBufferedEvents: Int): Flux<T> {
    return onBackpressureBuffer(maxBufferedEvents.coerceAtLeast(1))
}
