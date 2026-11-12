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
    /** 다른 workspace 의 호출은 취소할 수 없다(404). (authz-spec §5) */
    fun cancel(workspaceId: UUID, callLogId: UUID, reason: String): Unit {
        if (!llmCallLogRepository.existsByIdAndWorkspaceId(callLogId, workspaceId)) {
            throw CallLogNotFoundException(callLogId)
        }
        val cancelled = llmStreamCancelRegistry.cancel(callLogId, reason)
        if (!cancelled) {
            throw ActiveStreamNotFoundException(callLogId)
        }
        llmCallLogTxService.updateCancelRequested(callLogId, reason)
    }
}
