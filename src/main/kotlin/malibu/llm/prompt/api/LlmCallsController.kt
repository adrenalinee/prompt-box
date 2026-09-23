package malibu.llm.prompt.api

import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.OutputItemPartType
import malibu.llm.prompt.data.OutputItemType
import malibu.llm.prompt.data.entity.InputItem
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.OutputItem
import malibu.llm.prompt.data.entity.OutputItemPart
import malibu.llm.prompt.service.LlmCallCancelService
import malibu.llm.prompt.service.LlmCallLogService
import org.springframework.http.HttpStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/llm-calls")
class LlmCallsController(
    private val llmCallLogService: LlmCallLogService,
    private val llmCallCancelService: LlmCallCancelService,
) {

    @GetMapping
    fun listLogs(
        @RequestParam(required = false) workspaceId: UUID?,
        @RequestParam(required = false) modelId: Long?,
        @RequestParam(required = false) status: LlmCallStatus?,
        @RequestParam(required = false) promptId: UUID?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmCallLogResponse> =
        llmCallLogService.listLogs(workspaceId, modelId, status, promptId, pageable)
            .toResponse { it.toResponse() }

    @GetMapping("/{callLogId}")
    fun getLog(@PathVariable callLogId: UUID): LlmCallLogDetailResponse {
        val detail = llmCallLogService.getLog(callLogId)
        val partsByOutputId = detail.outputParts.groupBy { it.outputItem.id }
        return LlmCallLogDetailResponse(
            callLog = detail.log.toDetailResponse(),
            inputItems = detail.inputs.map { it.toResponse() },
            outputItems = detail.outputs.map { output ->
                output.toResponse(partsByOutputId[output.id] ?: emptyList())
            },
        )
    }

    @PostMapping("/{callLogId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancel(
        @PathVariable callLogId: UUID,
        @RequestBody(required = false) request: LlmCallCancelRequest?,
    ) {
        llmCallCancelService.cancel(
            callLogId = callLogId,
            reason = request?.reason ?: "cancelled by client",
        )
    }
}

data class LlmCallCancelRequest(
    val reason: String? = null,
)

data class LlmCallLogResponse(
    val id: UUID,
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,
    val promptRefId: Long?,
    val elapsed: Long,
    val status: LlmCallStatus,
    val errorCode: String?,
    val instructionsPreview: String?,
    val createdAt: Instant?,
)

data class LlmCallLogDetailResponse(
    val callLog: LlmCallLogDetailInfo,
    val inputItems: List<InputItemResponse>,
    val outputItems: List<OutputItemResponse>,
)

data class LlmCallLogDetailInfo(
    val id: UUID,
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,
    val promptRefId: Long?,
    val elapsed: Long,
    val status: LlmCallStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val llmCallOptions: String?,
    val instructions: String,
    val determinedModel: String?,
    val inputTokens: Long?,
    val cachedTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val createdAt: Instant?,
)

data class InputItemResponse(
    val id: Long,
    val position: Int,
    val role: InputRole,
    val renderedMessage: String,
    val createdAt: Instant?,
)

data class OutputItemResponse(
    val id: Long,
    val position: Int,
    val itemType: OutputItemType,
    val parts: List<OutputItemPartResponse>,
    val createdAt: Instant?,
)

data class OutputItemPartResponse(
    val id: Long,
    val partType: OutputItemPartType,
    val partIndex: Long,
    val text: String?,
    val createdAt: Instant?,
)

fun LlmCallLog.toResponse() = LlmCallLogResponse(
    id = requireNotNull(id),
    workspaceId = requireNotNull(workspace.id),
    llmModelId = requireNotNull(llmModelId),
    llmApiKeyId = requireNotNull(llmApiKeyId),
    promptRefId = promptRefId,
    elapsed = elapsed,
    status = status,
    errorCode = errorCode,
    instructionsPreview = instructionsPreview(instructions),
    createdAt = createdAt,
)

private fun instructionsPreview(instructions: String?, maxLength: Int = 20): String? {
    if (instructions.isNullOrBlank()) {
        return null
    }
    return if (instructions.length <= maxLength) {
        instructions
    } else {
        "${instructions.substring(0, maxLength)}..."
    }
}

fun LlmCallLog.toDetailResponse() = LlmCallLogDetailInfo(
    id = requireNotNull(id),
    workspaceId = requireNotNull(workspace.id),
    llmModelId = requireNotNull(llmModelId),
    llmApiKeyId = requireNotNull(llmApiKeyId),
    promptRefId = promptRefId,
    elapsed = elapsed,
    status = status,
    errorCode = errorCode,
    errorMessage = errorMessage,
    llmCallOptions = llmCallOptions,
    instructions = instructions,
    inputTokens = inputTokens,
    determinedModel = determinedModel,
    cachedTokens = cachedTokens,
    outputTokens = outputTokens,
    reasoningTokens = reasoningTokens,
    totalTokens = totalTokens,
    createdAt = createdAt,
)

private fun InputItem.toResponse() = InputItemResponse(
    id = id,
    position = position,
    role = role,
    renderedMessage = renderedMessage,
    createdAt = createdAt,
)

private fun OutputItem.toResponse(parts: List<OutputItemPart>) = OutputItemResponse(
    id = id,
    position = position,
    itemType = itemType,
    parts = parts.map { it.toResponse() },
    createdAt = createdAt,
)

private fun OutputItemPart.toResponse() = OutputItemPartResponse(
    id = id,
    partType = partType,
    partIndex = partIndex,
    text = text,
    createdAt = createdAt,
)
