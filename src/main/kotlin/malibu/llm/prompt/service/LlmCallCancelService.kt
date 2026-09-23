package malibu.llm.prompt.service

import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.error.ActiveStreamNotFoundException
import malibu.llm.prompt.error.CallLogNotFoundException
import malibu.llm.streamclient.LlmStreamCancelRegistry
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LlmCallCancelService(
    private val llmCallLogRepository: LlmCallLogRepository,
    private val llmCallLogTxService: LlmCallLogTxService,
    private val llmStreamCancelRegistry: LlmStreamCancelRegistry,
) {
    fun cancel(callLogId: UUID, reason: String): Unit {
        if (!llmCallLogRepository.existsById(callLogId)) {
            throw CallLogNotFoundException(callLogId)
        }
        val cancelled = llmStreamCancelRegistry.cancel(callLogId, reason)
        if (!cancelled) {
            throw ActiveStreamNotFoundException(callLogId)
        }
        llmCallLogTxService.updateCancelRequested(callLogId, reason)
    }
}
