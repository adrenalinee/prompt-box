package amlibu.llm.prompt

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.OutputItem
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.OutputItemRepository
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
