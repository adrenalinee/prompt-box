package mystix.prompt.llm

import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.test.StepVerifier
import java.time.Duration
import kotlin.test.assertEquals

class LlmStreamBackpressureTest {

    @Test
    fun `effectiveMaxBufferedEvents uses override before default request`() {
        val base = LlmStreamOptions(maxBufferedEvents = 16)
        val override = LlmStreamOptions(maxBufferedEvents = 4)

        assertEquals(4, effectiveMaxBufferedEvents(override, base))
        assertEquals(16, effectiveMaxBufferedEvents(null, base))
        assertEquals(DEFAULT_MAX_BUFFERED_EVENTS, effectiveMaxBufferedEvents(LlmStreamOptions(maxBufferedEvents = 0), null))
    }

    @Test
    fun `error overflow strategy fails instead of buffering without bound`() {
        val source = Flux.create<Int>({ sink ->
            sink.next(0)
            sink.next(1)
            sink.complete()
        }, FluxSink.OverflowStrategy.ERROR)

        StepVerifier.create(source, 1)
            .expectNext(0)
            .expectError()
            .verify(Duration.ofSeconds(1))
    }
}
