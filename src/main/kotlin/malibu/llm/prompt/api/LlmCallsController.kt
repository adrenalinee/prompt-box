package malibu.llm.prompt.api

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.dto.InputItemResponse
import malibu.llm.prompt.data.dto.LlmCallCancelRequest
import malibu.llm.prompt.data.dto.LlmCallLogDetailInfo
import malibu.llm.prompt.data.dto.LlmCallLogDetailResponse
import malibu.llm.prompt.data.dto.LlmCallLogResponse
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.dto.OutputItemPartResponse
import malibu.llm.prompt.data.dto.OutputItemResponse
import malibu.llm.prompt.data.entity.InputItem
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.OutputItem
import malibu.llm.prompt.data.entity.OutputItemPart
import malibu.llm.prompt.service.LlmCallCancelService
import malibu.llm.prompt.service.LlmCallLogService
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/llm-calls")
class LlmCallsController(
    private val llmCallLogService: LlmCallLogService,
    private val llmCallCancelService: LlmCallCancelService,
) {

    @GetMapping
    fun listLogs(
        @ParameterObject searchSpec: LlmCallSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmCallLogResponse> =
        llmCallLogService.listLogs(searchSpec, pageable)
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
