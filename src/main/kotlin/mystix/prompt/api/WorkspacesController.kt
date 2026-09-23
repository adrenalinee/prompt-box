package mystix.prompt.api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import mu.KotlinLogging
import mystix.prompt.ExecuteInputItem
import mystix.prompt.ExecuteOverrideOptions
import mystix.prompt.ExecuteRequest
import mystix.prompt.ExecuteStreamingOrchestrator
import mystix.prompt.data.LlmCallStatus
import mystix.prompt.data.entity.DefaultLlmCallOptions
import mystix.prompt.data.entity.Workspace
import mystix.prompt.llm.LlmStreamEvent
import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.VerbosityType
import mystix.prompt.service.LlmCallLogService
import mystix.prompt.service.LlmCatalogService
import mystix.prompt.service.PromptService
import mystix.prompt.service.WorkspaceService
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/workspaces")
class WorkspacesController(
    private val workspaceService: WorkspaceService,
    private val promptService: PromptService,
    private val llmCatalogService: LlmCatalogService,
    private val llmCallLogService: LlmCallLogService,
    private val executeStreamingOrchestrator: ExecuteStreamingOrchestrator,
) {
    private val logger = KotlinLogging.logger {}


    @GetMapping
    fun list(
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<WorkspaceResponse> =
        workspaceService.list(name, pageable).toResponse { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: WorkspaceCreateRequest): WorkspaceResponse {
        val workspace = workspaceService.create(request.name, request.description, request.defaultModelId)
        return workspace.toResponse()
    }

    @GetMapping("/{workspaceId}")
    fun get(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false, defaultValue = "false") includeDefaultOptions: Boolean,
    ): WorkspaceDetailResponse {
        val workspace = workspaceService.get(workspaceId)
        val defaultOptions = if (includeDefaultOptions) {
            workspaceService.findDefaultOptions(workspaceId)?.toResponse()
        } else {
            null
        }
        return workspace.toDetailResponse(defaultOptions)
    }

    @PatchMapping("/{workspaceId}")
    fun update(
        @PathVariable workspaceId: UUID,
        @RequestBody request: WorkspaceUpdateRequest,
    ): WorkspaceResponse {
        val workspace = workspaceService.update(workspaceId, request.name, request.description, request.defaultModelId)
        return workspace.toResponse()
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workspaceId: UUID) {
        workspaceService.delete(workspaceId)
    }

    @GetMapping("/{workspaceId}/prompts")
    fun listPrompts(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptResponse> =
        promptService.listPrompts(workspaceId, name, pageable).toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}/models")
    fun listModels(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) vendorId: UUID?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmModelResponse> =
        llmCatalogService.listModelsForWorkspace(workspaceId, vendorId, name, pageable).toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}/llm-api-keys")
    fun listApiKeys(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) vendorId: UUID?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmApiKeyResponse> =
        llmCatalogService.listApiKeys(workspaceId, vendorId, name, pageable).toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}/llm-calls")
    fun listLogs(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) modelId: Long?,
        @RequestParam(required = false) status: LlmCallStatus?,
        @RequestParam(required = false) promptId: UUID?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmCallLogResponse> =
        llmCallLogService.listLogs(workspaceId, modelId, status, promptId, pageable)
            .toResponse { it.toResponse() }

    @PostMapping("/{workspaceId}/llm-calls", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
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
    ): reactor.core.publisher.Flux<ServerSentEvent<LlmStreamEvent>> {
        logger.info { "start"}
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

    @GetMapping("/{workspaceId}/default-options")
    fun getDefaultOptions(@PathVariable workspaceId: UUID): DefaultOptionsResponse {
        val options = workspaceService.getDefaultOptions(workspaceId)
        return options.toResponse()
    }

    @PutMapping("/{workspaceId}/default-options")
    fun upsertDefaultOptions(
        @PathVariable workspaceId: UUID,
        @RequestBody request: DefaultOptionsRequest,
    ): DefaultOptionsResponse {
        val options = workspaceService.upsertDefaultOptions(
            workspaceId = workspaceId,
            temperature = request.temperature,
            topP = request.topP,
            topK = request.topK,
            includeThoughts = request.includeThoughts,
            maxTokens = request.maxTokens,
            textFormat = request.textFormat,
            effort = request.effort,
            verbosity = request.verbosity,
            summary = request.summary,
        )
        return options.toResponse()
    }
}

data class WorkspaceCreateRequest(
    val name: String,
    val description: String? = null,
    val defaultModelId: Long? = null,
)

data class WorkspaceUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val defaultModelId: Long? = null,
)

data class WorkspaceResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val defaultModelId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class WorkspaceDetailResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val defaultModelId: Long?,
    val defaultLlmCallOptions: DefaultOptionsResponse?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class DefaultOptionsRequest(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Double? = null,
    val includeThoughts: Boolean? = null,
    val maxTokens: Long? = null,
    val textFormat: String? = null,
    val effort: ReasoningEffortType? = null,
    val verbosity: VerbosityType? = null,
    val summary: SummaryType? = null,
)

data class DefaultOptionsResponse(
    val workspaceId: UUID,
    val temperature: Double?,
    val topP: Double?,
    val topK: Double?,
    val includeThoughts: Boolean?,
    val maxTokens: Long?,
    val textFormat: String?,
    val effort: ReasoningEffortType?,
    val verbosity: VerbosityType?,
    val summary: SummaryType?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class ExecuteApiRequest(
    val llmModelId: Long,
    val llmApiKeyId: UUID,
    val promptRefId: Long? = null,
    val rawInstructionsText: String? = null,
    val inputItems: List<InputItemRequest> = emptyList(),
    val overrideOptions: OverrideOptionsRequest? = null,
)

data class InputItemRequest(
    val role: mystix.prompt.data.InputRole,
    val message: String,
)

data class OverrideOptionsRequest(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Double? = null,
    val includeThoughts: Boolean? = null,
    val maxTokens: Long? = null,
    val textFormat: String? = null,
    val effort: ReasoningEffortType? = null,
    val verbosity: VerbosityType? = null,
    val summary: SummaryType? = null,
)

private fun Workspace.toResponse() = WorkspaceResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    defaultModelId = defaultModel?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Workspace.toDetailResponse(defaultOptions: DefaultOptionsResponse?) = WorkspaceDetailResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    defaultModelId = defaultModel?.id,
    defaultLlmCallOptions = defaultOptions,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun DefaultLlmCallOptions.toResponse() = DefaultOptionsResponse(
    workspaceId = requireNotNull(workspaceId),
    temperature = temperature,
    topP = topP,
    topK = topK,
    includeThoughts = includeThoughts,
    maxTokens = maxTokens,
    textFormat = textFormat,
    effort = effort,
    verbosity = verbosity,
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
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
