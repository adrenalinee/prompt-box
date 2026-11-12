package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import malibu.llm.prompt.ExecuteInputItem
import malibu.llm.prompt.ExecuteOverrideOptions
import malibu.llm.prompt.ExecuteRequest
import malibu.llm.prompt.ExecuteStreamingOrchestrator
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.dto.ExecuteApiRequest
import malibu.llm.prompt.data.dto.InputItemRequest
import malibu.llm.prompt.data.dto.OverrideOptionsRequest
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
import malibu.llm.prompt.service.LlmCallLogDetail
import malibu.llm.prompt.service.LlmCallLogService
import malibu.llm.streamclient.LlmStreamEvent
import mu.KotlinLogging
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import java.util.*

/**
 * LLM 호출(SSE 실행)과 호출 로그.
 *
 * TODO(authz 5단계): callLogId 를 workspaceId 조건으로 조회하도록 서비스를 바꾼다(IDOR).
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/llm-calls")
class LlmCallsController(
    private val llmCallLogService: LlmCallLogService,
    private val llmCallCancelService: LlmCallCancelService,
    private val executeStreamingOrchestrator: ExecuteStreamingOrchestrator,
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.CALL_LOG_READ}')")
    fun listLogs(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) modelId: Long?,
        @RequestParam(required = false) status: LlmCallStatus?,
        @RequestParam(required = false) promptId: UUID?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmCallLogResponse> =
        llmCallLogService.listLogs(
            LlmCallSearchSpec(workspaceId = workspaceId, modelId = modelId, status = status, promptId = promptId),
            pageable,
        ).toResponse { it.toResponse() }

    @GetMapping("/{callLogId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.CALL_LOG_READ}')")
    fun getLog(
        @PathVariable workspaceId: UUID,
        @PathVariable callLogId: UUID,
    ): LlmCallLogDetailResponse =
        llmCallLogService.getLog(callLogId).toResponse()

    @PostMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.LLM_EXECUTE}')")
    @Operation(
        summary = "Execute LLM call (streaming)",
        description = """
            Returns Server-Sent Events (SSE). Each SSE event name is the payload's `type` value.

            Event types include `log.created`, `response.created`, message content lifecycle events,
            reasoning lifecycle events, `response.function_call.done`, and `response.completed`.
            `response.function_call.done` is emitted after a configured function callback finishes. On
            success it includes `output` and `delivery`; on failure it includes `errorMessage`.
            If `delivery` is `RETURN_DIRECT`, the stream can finish after synthetic message events
            without a `response.completed` event.
        """,
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream of LLM call lifecycle, output, reasoning, and function call events.",
                content = [
                    Content(
                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                        schema = Schema(
                            discriminatorProperty = "type",
                            discriminatorMapping = [
                                DiscriminatorMapping(
                                    value = "log.created",
                                    schema = LlmStreamEvent.LogCreated::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.created",
                                    schema = LlmStreamEvent.ResponseCreated::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.message.added",
                                    schema = LlmStreamEvent.ResponseMessageAdded::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.message.content_part.added",
                                    schema = LlmStreamEvent.MessageContentPartAdded::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.message.content_part.done",
                                    schema = LlmStreamEvent.MessageContentPartDone::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.message.delta",
                                    schema = LlmStreamEvent.MessageDelta::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.message.done",
                                    schema = LlmStreamEvent.ResponseMessageDone::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.reasoning.added",
                                    schema = LlmStreamEvent.ResponseReasoningAdded::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.reasoning.summary_part.added",
                                    schema = LlmStreamEvent.ReasoningSummaryPartAdded::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.reasoning.summary_part.done",
                                    schema = LlmStreamEvent.ReasoningSummaryPartDone::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.reasoning.delta",
                                    schema = LlmStreamEvent.ReasoningDelta::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.reasoning.done",
                                    schema = LlmStreamEvent.ResponseReasoningDone::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.function_call.done",
                                    schema = LlmStreamEvent.FunctionCallObserved::class,
                                ),
                                DiscriminatorMapping(
                                    value = "response.completed",
                                    schema = LlmStreamEvent.ResponseCompleted::class,
                                ),
                            ],
                            oneOf = [
                                LlmStreamEvent.LogCreated::class,
                                LlmStreamEvent.ResponseCreated::class,
                                LlmStreamEvent.ResponseMessageAdded::class,
                                LlmStreamEvent.MessageContentPartAdded::class,
                                LlmStreamEvent.MessageContentPartDone::class,
                                LlmStreamEvent.MessageDelta::class,
                                LlmStreamEvent.ResponseMessageDone::class,
                                LlmStreamEvent.ResponseReasoningAdded::class,
                                LlmStreamEvent.ReasoningSummaryPartAdded::class,
                                LlmStreamEvent.ReasoningSummaryPartDone::class,
                                LlmStreamEvent.ReasoningDelta::class,
                                LlmStreamEvent.ResponseReasoningDone::class,
                                LlmStreamEvent.FunctionCallObserved::class,
                                LlmStreamEvent.ResponseCompleted::class,
                            ]
                        ),
                    ),
                ],
            ),
            ApiResponse(responseCode = "400", description = "Bad request"),
        ],
    )
    fun execute(
        @PathVariable workspaceId: UUID,
        @RequestBody request: ExecuteApiRequest,
    ): Flux<ServerSentEvent<LlmStreamEvent>> {
        logger.info { "request: $request" }

        return executeStreamingOrchestrator.executeStreaming(
            workspaceId,
            ExecuteRequest(
                workspaceId = workspaceId,
                llmModelId = request.llmModelId,
                llmApiKeyId = request.llmApiKeyId,
                promptRefId = request.promptRefId,
                rawInstructionsText = request.rawInstructionsText,
                inputItems = request.inputItems.map { it.toExecuteInputItem() },
                overrideOptions = request.overrideOptions?.toExecuteOverrideOptions(),
            )
        ).map { event ->
            ServerSentEvent.builder(event)
                .event(event.type)
                .build()
        }
    }

    @PostMapping("/{callLogId}/cancel")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.LLM_EXECUTE}')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancel(
        @PathVariable workspaceId: UUID,
        @PathVariable callLogId: UUID,
        @RequestBody(required = false) request: LlmCallCancelRequest?,
    ) {
        llmCallCancelService.cancel(
            callLogId = callLogId,
            reason = request?.reason ?: "cancelled by client",
        )
    }
}

fun LlmCallLogDetail.toResponse(): LlmCallLogDetailResponse {
    val partsByOutputId = outputParts.groupBy { it.outputItem.id }
    return LlmCallLogDetailResponse(
        callLog = log.toDetailResponse(),
        inputItems = inputs.map { it.toResponse() },
        outputItems = outputs.map { output ->
            output.toResponse(partsByOutputId[output.id] ?: emptyList())
        },
    )
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

private fun InputItemRequest.toExecuteInputItem() = ExecuteInputItem(
    role = role,
    message = message,
)

private fun OverrideOptionsRequest.toExecuteOverrideOptions() = ExecuteOverrideOptions(
    temperature = temperature,
    topP = topP,
    topK = topK,
    includeThoughts = includeThoughts,
    maxTokens = maxTokens,
    textFormat = textFormat,
    effort = effort,
    verbosity = verbosity,
    summary = summary,
)
