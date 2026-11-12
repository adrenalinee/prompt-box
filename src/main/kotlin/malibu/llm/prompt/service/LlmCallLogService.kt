package malibu.llm.prompt.service

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
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
        searchSpec: LlmCallSearchSpec,
        pageable: Pageable,
    ): Page<LlmCallLog> {
        return llmCallLogRepository.searchLogs(searchSpec, pageable)
    }

    /** IDOR 방어: 경로의 workspace 에 속한 로그만. 자식(input/output)은 이 로그를 통해서만 조회한다. (authz-spec §5) */
    @Transactional(readOnly = true)
    fun getLog(workspaceId: UUID, callLogId: UUID): LlmCallLogDetail =
        detailOf(llmCallLogRepository.findByIdAndWorkspaceId(callLogId, workspaceId) ?: throw CallLogNotFoundException(callLogId))

    /** 플랫폼 전체 조회(/admin/llm-calls/{id}) 전용. */
    @Transactional(readOnly = true)
    fun getLogForPlatform(callLogId: UUID): LlmCallLogDetail =
        detailOf(llmCallLogRepository.findById(callLogId).orElseThrow { CallLogNotFoundException(callLogId) })

    private fun detailOf(log: LlmCallLog): LlmCallLogDetail {
        val callLogId = requireNotNull(log.id)
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
