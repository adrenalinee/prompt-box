package mystix.prompt

import mystix.prompt.data.LlmCallStatus
import mystix.prompt.data.entity.LlmCallLog
import mystix.prompt.data.entity.OutputItem
import mystix.prompt.data.repo.LlmCallLogRepository
import mystix.prompt.data.repo.OutputItemRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.util.UUID

open class ExecuteStreamingTestSupport(
    private val llmCallLogRepository: LlmCallLogRepository,
    private val outputItemRepository: OutputItemRepository,
) {

    protected fun awaitCallLog(workspaceId: UUID, status: LlmCallStatus): LlmCallLog? {
        val deadline = System.currentTimeMillis() + 2000
        var latest: LlmCallLog? = null
        while (System.currentTimeMillis() < deadline) {
            val logs = llmCallLogRepository.findByWorkspaceId(
                workspaceId,
                PageRequest.of(0, 1, Sort.by("createdAt").descending())
            )
            latest = logs.content.firstOrNull()
            if (latest != null && latest.status == status) {
                return latest
            }
            Thread.sleep(50)
        }
        return latest
    }

    protected fun awaitOutputs(callLogId: UUID): List<OutputItem> {
        val deadline = System.currentTimeMillis() + 2000
        var outputs = outputItemRepository.findByLlmCallLogIdOrderByPositionAsc(callLogId)
        while (System.currentTimeMillis() < deadline && outputs.isEmpty()) {
            Thread.sleep(50)
            outputs = outputItemRepository.findByLlmCallLogIdOrderByPositionAsc(callLogId)
        }
        return outputs
    }
}
