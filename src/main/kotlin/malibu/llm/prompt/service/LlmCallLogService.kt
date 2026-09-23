package malibu.llm.prompt.service

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.entity.InputItem
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.OutputItem
import malibu.llm.prompt.data.entity.OutputItemPart
import malibu.llm.prompt.data.repo.InputItemRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.OutputItemPartRepository
import malibu.llm.prompt.data.repo.OutputItemRepository
import malibu.llm.prompt.error.CallLogNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LlmCallLogService(
    private val llmCallLogRepository: LlmCallLogRepository,
    private val inputItemRepository: InputItemRepository,
    private val outputItemRepository: OutputItemRepository,
    private val outputItemPartRepository: OutputItemPartRepository,
) {

    @Transactional(readOnly = true)
    fun listLogs(
        workspaceId: UUID?,
        modelId: Long?,
        status: LlmCallStatus?,
        promptId: UUID?,
        pageable: Pageable,
    ): Page<LlmCallLog> {
        return llmCallLogRepository.searchLogs(workspaceId, modelId, status, promptId, pageable)
    }

    @Transactional(readOnly = true)
    fun getLog(callLogId: UUID): LlmCallLogDetail {
        val log = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        val inputs = inputItemRepository.findByLlmCallLogIdOrderByPositionAsc(callLogId)
        val outputs = outputItemRepository.findByLlmCallLogIdOrderByPositionAsc(callLogId)
        val outputItemIds = outputs.mapNotNull { it.id }
        val parts = if (outputItemIds.isEmpty()) {
            emptyList()
        } else {
            outputItemPartRepository.findByOutputItemIdInOrderByPartIndexAsc(outputItemIds)
        }
        return LlmCallLogDetail(log, inputs, outputs, parts)
    }
}

data class LlmCallLogDetail(
    val log: LlmCallLog,
    val inputs: List<InputItem>,
    val outputs: List<OutputItem>,
    val outputParts: List<OutputItemPart>,
)
