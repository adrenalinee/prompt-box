package mystix.prompt.llm

import reactor.core.publisher.Sinks
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class LlmStreamCancelRegistry {
    private val signals = ConcurrentHashMap<UUID, Sinks.One<String>>()

    fun register(callLogId: UUID, signal: Sinks.One<String>) {
        signals[callLogId] = signal
    }

    fun cancel(callLogId: UUID, reason: String): Boolean {
        val signal = signals[callLogId] ?: return false
        signal.tryEmitValue(reason)
        return true
    }

    fun remove(callLogId: UUID) {
        signals.remove(callLogId)
    }
}
