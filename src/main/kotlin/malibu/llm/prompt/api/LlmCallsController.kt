package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
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
 * callLogId 는 경로의 workspaceId 조건으로만 조회한다. (authz-spec §5)
 */
@Tag(name = "LLM 호출", description = "LLM 스트리밍 호출(SSE), 호출 취소, 호출 로그.")
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
    @Operation(summary = "호출 로그 목록", description = "modelId, status, promptId 로 거른다.")
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
    @Operation(summary = "호출 로그 상세", description = "호출 정보와 입력 / 출력 item, 토큰 사용량을 포함한다.")
    fun getLog(
        @PathVariable workspaceId: UUID,
        @PathVariable callLogId: UUID,
    ): LlmCallLogDetailResponse =
        llmCallLogService.getLog(workspaceId, callLogId).toResponse()

    @PostMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.LLM_EXECUTE}')")
    @Operation(
        summary = "LLM 호출 (스트리밍)",
        description = """
            모델을 호출하고 결과를 Server-Sent Events(SSE)로 스트리밍한다. 호출 결과는 호출 로그로 저장된다.

            - promptRefId 를 주면 그 ref 의 instructions 와 추가 입력 항목으로 호출한다. 이때 rawInstructionsText 와
              inputItems 는 쓰지 않는다.
            - promptRefId 가 없으면 rawInstructionsText 와 inputItems 로 호출한다. 둘 다 비어 있으면 오류.
            - 호출 옵션은 workspace 기본 옵션에 요청의 overrideOptions 를 덮어쓴 뒤, 모델 타입에 맞는 것만 쓴다.
            - 모델 벤더와 API 키 벤더가 다르면 400. 벤더에 따라 지원하지 않는 입력 조합도 400
              (예: google 은 instructions 만으로 호출할 수 없다).

            SSE 이벤트 이름은 payload 의 `type` 값과 같다. 첫 이벤트는 `log.created` 이며, 여기서 받은 callLogId 로
            호출 취소(`POST .../{callLogId}/cancel`)와 로그 조회를 한다. 이어서 `response.created`, 메시지 / reasoning
            수명주기 이벤트, `response.function_call.done`, `response.completed` 가 온다.
            `response.function_call.done` 은 설정된 함수 콜백이 끝난 뒤 나간다. 성공하면 `output` 과 `delivery` 가,
            실패하면 `errorMessage` 가 들어 있다. `delivery` 가 `RETURN_DIRECT` 면 합성 메시지 이벤트 뒤에
            `response.completed` 없이 스트림이 끝날 수 있다.
        """,
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "LLM 호출 수명주기, 출력, reasoning, 함수 호출 이벤트의 SSE 스트림.",
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
            ApiResponse(responseCode = "400", description = "잘못된 요청(벤더 불일치, 지원하지 않는 입력 등)"),
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
    @Operation(summary = "호출 취소", description = "진행 중인 스트리밍 호출을 취소한다. callLogId 는 스트림의 첫 이벤트(log.created)에서 받는다. 진행 중인 스트림이 없으면 404.")
    fun cancel(
        @PathVariable workspaceId: UUID,
        @PathVariable callLogId: UUID,
        @RequestBody(required = false) request: LlmCallCancelRequest?,
    ) {
        llmCallCancelService.cancel(
            workspaceId = workspaceId,
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
