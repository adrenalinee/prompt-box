package mystix.prompt.service

import mystix.prompt.data.repo.LlmCallLogRepository
import mystix.prompt.error.ActiveStreamNotFoundException
import mystix.prompt.error.CallLogNotFoundException
import mystix.prompt.llm.LlmStreamCancelRegistry
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
