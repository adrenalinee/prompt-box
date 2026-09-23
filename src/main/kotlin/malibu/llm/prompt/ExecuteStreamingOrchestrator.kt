package malibu.llm.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.errors.OpenAIServiceException
import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.LlmModelType
import malibu.llm.prompt.data.OutputItemPartType
import malibu.llm.prompt.data.OutputItemType
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.data.repo.*
import malibu.llm.prompt.error.*
import malibu.llm.prompt.security.ApiKeyCipher
import malibu.llm.prompt.service.LlmCallLogTxService
import malibu.llm.streamclient.*
import malibu.llm.streamclient.exception.InputEmptyException
import malibu.llm.streamclient.xai.XaiApiException
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface ExecuteStreamingOrchestrator {
    fun executeStreaming(workspaceId: UUID, request: ExecuteRequest): Flux<LlmStreamEvent>
}

@Component
class ExecuteStreamingOrchestratorImpl(
    private val workspaceRepository: WorkspaceRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val promptRefRepository: PromptRefRepository,
    private val additionalInputItemRepository: AdditionalInputItemRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val llmCallLogService: LlmCallLogTxService,
    private val llmStreamClientProvider: LlmStreamClientProvider,
    private val llmStreamCancelRegistry: LlmStreamCancelRegistry,
    private val apiKeyCipher: ApiKeyCipher,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ExecuteStreamingOrchestrator {

    override fun executeStreaming(workspaceId: UUID, request: ExecuteRequest): Flux<LlmStreamEvent> {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        val model = llmModelRepository.findById(request.llmModelId)
            .orElseThrow { ModelNotFoundException(request.llmModelId) }
        val apiKey = llmApiKeyRepository.findById(request.llmApiKeyId)
            .orElseThrow { ApiKeyNotFoundException(request.llmApiKeyId) }

        if (requireNotNull(model.llmVendor.id) != requireNotNull(apiKey.llmVendor.id)) {
            throw VendorMismatchException()
        }
        if (requireNotNull(apiKey.workspace.id) != workspaceId) {
            throw WorkspaceMismatchException()
        }

        val resolvedRef = resolveRef(request)
        if (resolvedRef != null &&
            requireNotNull(resolvedRef.prompt.workspace.id) != workspaceId
        ) {
            throw WorkspaceMismatchException()
        }

        val resolvedInstructions = resolveInstructions(request, resolvedRef)
        val messages = buildMessages(resolvedRef, request.inputItems)
        if (resolvedInstructions.isNullOrBlank() && messages.isEmpty()) {
            throw InputEmptyException()
        }
        val resolvedOptions = resolveOptions(workspaceId, request.overrideOptions)
        val filteredOptions = resolvedOptions.filterByModelType(model.modelType)
        val llmCallOptionsJson = filteredOptions.toJson(objectMapper)

        val vendorName = model.llmVendor.name
        validateVendorSpecificInput(vendorName, messages)
        val decryptedApiKey = apiKeyCipher.decrypt(apiKey.value)
        val client = llmStreamClientProvider.getOrCreate(request.llmApiKeyId.toString(), apiKey.llmVendor.name, decryptedApiKey)
        println("client: $client")

        val completed = AtomicBoolean(false)
        val itemsParts =
            Collections.synchronizedMap(mutableMapOf<String, MutableList<LlmCallLogTxService.OutputItemPartPayload>>())
        val itemTypes = Collections.synchronizedMap(mutableMapOf<String, OutputItemType>())
        val itemIndexes = Collections.synchronizedMap(mutableMapOf<String, Long>())
        val determinedModel = AtomicReference<String>()
        val usageTokens = AtomicReference<LlmCallLogTxService.TokenUsagePayload?>(null)

        val streamRequest = LlmStreamRequest(
            modelKey = model.modelKey,
            apiKey = decryptedApiKey,
            instructions = resolvedInstructions,
            inputs = messages.map { LlmMessageInputItem(role = it.role.toMessageRole(), content = it.message) },
            options = filteredOptions.toStreamOptions(),
        )

        return Flux.defer {
            val startNs = System.nanoTime()
            val callLog = llmCallLogService.createLog(
                workspace = workspace,
                model = model,
                apiKey = apiKey,
                promptRef = resolvedRef,
                instructions = resolvedInstructions ?: "",
                llmCallOptions = llmCallOptionsJson,
                messages = messages,
            )
            val callLogId = requireNotNull(callLog.id)
            val createdEvent = LlmStreamEvent.LogCreated(
                logId = callLogId.toString(),
                createdAt = callLog.createdAt?.toString(),
            )

            val cancelSignal = Sinks.one<String>()
            val cancelReason = AtomicReference<String?>(null)
            llmStreamCancelRegistry.register(callLogId, cancelSignal)

            val stream = if (client == null) {
                llmCallLogService.updateErrorLog(
                    callLogId = callLogId,
                    elapsedMs = 0,
                    outputItems = emptyList(),
                    determinedModel = null,
                    errorMessage = UnsupportedVendorException(vendorName).message,
                )
                Flux.error(UnsupportedVendorException(vendorName))
            } else {
                llmCallLogService.updateRunningLog(callLogId)
                client.stream(streamRequest)
            }

            val cancelMono = cancelSignal.asMono()
                .doOnNext { reason -> cancelReason.set(reason) }

            Flux.concat(Flux.just(createdEvent), stream)
                .takeUntilOther(cancelMono)
                .doOnNext { event ->
                    when (event) {
                        is LlmStreamEvent.ResponseMessageAdded -> {
                            itemTypes[event.itemId] = OutputItemType.MESSAGE
                            itemIndexes[event.itemId] = event.itemIndex
                        }
                        is LlmStreamEvent.ResponseReasoningAdded -> {
                            itemTypes[event.itemId] = OutputItemType.REASONING
                            itemIndexes[event.itemId] = event.itemIndex
                        }
                        is LlmStreamEvent.ResponseMessageDone -> {
                            itemTypes[event.itemId] = OutputItemType.MESSAGE
                            itemIndexes[event.itemId] = event.itemIndex
                        }
                        is LlmStreamEvent.ResponseReasoningDone -> {
                            itemTypes[event.itemId] = OutputItemType.REASONING
                            itemIndexes[event.itemId] = event.itemIndex
                        }
                        is LlmStreamEvent.MessageContentPartDone -> {
                            itemTypes[event.itemId] = OutputItemType.MESSAGE
                            event.itemIndex?.let { itemIndexes[event.itemId] = it }
                            val parts = itemsParts.getOrPut(event.itemId) { mutableListOf() }
                            parts.add(
                                LlmCallLogTxService.OutputItemPartPayload(
                                    partType = OutputItemPartType.MESSAGE_CONTENT,
                                    partIndex = event.partIndex,
                                    text = event.text,
                                )
                            )
                        }
                        is LlmStreamEvent.ReasoningSummaryPartDone -> {
                            itemTypes[event.itemId] = OutputItemType.REASONING
                            itemIndexes[event.itemId] = event.itemIndex
                            val parts = itemsParts.getOrPut(event.itemId) { mutableListOf() }
                            parts.add(
                                LlmCallLogTxService.OutputItemPartPayload(
                                    partType = OutputItemPartType.REASONING_SUMMARY,
                                    partIndex = event.partIndex,
                                    text = event.text,
                                )
                            )
                        }
                        is LlmStreamEvent.ResponseCreated -> {
                            determinedModel.set(event.model)
                        }
                        is LlmStreamEvent.ResponseCompleted -> {
                            usageTokens.set(
                                LlmCallLogTxService.TokenUsagePayload(
                                    inputTokens = event.inputTokens,
                                    cachedTokens = event.cachedTokens,
                                    outputTokens = event.outputTokens,
                                    reasoningTokens = event.reasoningTokens,
                                    totalTokens = event.totalTokens,
                                )
                            )
                        }
                        else -> {}
                    }
                }
                .doOnError { ex ->
                    if (completed.compareAndSet(false, true)) {
                        val elapsedMs = elapsedMs(startNs)
                        val resolvedOutputItems = resolvedOutputItems(itemsParts, itemIndexes, itemTypes)
                        llmCallLogService.updateErrorLog(
                            callLogId = callLogId,
                            elapsedMs = elapsedMs,
                            outputItems = resolvedOutputItems,
                            determinedModel = determinedModel.get(),
                            errorMessage = ex.stackTraceToString(),
                        )

//                        val formattedError = formatLlmError(ex)
//                        if (ex is TimeoutException || ex.cause is TimeoutException) {
//                            llmCallLogService.updateTimeoutLog(
//                                callLogId = callLogId,
//                                elapsedMs = elapsedMs,
//                                outputItems = resolvedOutputItems,
//                                determinedModel = determinedModel.get(),
//                                errorMessage = formattedError,
//                            )
//                        } else {
//                            llmCallLogService.updateErrorLog(
//                                callLogId = callLogId,
//                                elapsedMs = elapsedMs,
//                                outputItems = resolvedOutputItems,
//                                determinedModel = determinedModel.get(),
//                                errorMessage = formattedError,
//                            )
//                        }
                    }
                }
                .doOnComplete {
                    if (completed.compareAndSet(false, true)) {
                        val elapsedMs = elapsedMs(startNs)
                        val resolvedOutputItems = resolvedOutputItems(itemsParts, itemIndexes, itemTypes)

                        val cancelledReason = cancelReason.get()
//                        println("cancelledReason: $cancelledReason")
                        if (cancelledReason != null) {
                            llmCallLogService.updateCancelledLog(
                                callLogId = callLogId,
                                elapsedMs = elapsedMs,
                                outputItems = resolvedOutputItems,
                                determinedModel = determinedModel.get(),
                                errorMessage = cancelledReason,
                            )
                        } else {
                            llmCallLogService.updateSuccessLog(
                                callLogId = callLogId,
                                elapsedMs = elapsedMs,
                                outputItems = resolvedOutputItems,
                                determinedModel = determinedModel.get(),
                                tokenUsage = usageTokens.get(),
                            )
                        }
                    }
                }
                .doFinally { signalType ->
                    llmStreamCancelRegistry.remove(callLogId)
                    if (signalType == SignalType.CANCEL && completed.compareAndSet(false, true)) {
                        val elapsedMs = elapsedMs(startNs)
                        val resolvedOutputItems = resolvedOutputItems(itemsParts, itemIndexes, itemTypes)
                        llmCallLogService.updateCancelledLog(
                            callLogId = callLogId,
                            elapsedMs = elapsedMs,
                            outputItems = resolvedOutputItems,
                            determinedModel = determinedModel.get(),
                            errorMessage = cancelReason.get() ?: "cancelled",
                        )
                    }
                }
        }
    }

    private fun resolvedOutputItems(
        itemsParts: Map<String, MutableList<LlmCallLogTxService.OutputItemPartPayload>>,
        itemIndexes: Map<String, Long>,
        itemTypes: Map<String, OutputItemType>,
    ): List<LlmCallLogTxService.OutputItemPayload> {
        return itemsParts.entries
            .sortedWith(compareBy { itemIndexes[it.key] ?: Long.MAX_VALUE })
            .map { (itemId, parts) ->
                LlmCallLogTxService.OutputItemPayload(
                    itemType = itemTypes[itemId] ?: OutputItemType.UNKNOWN,
                    parts = parts.sortedWith(
                        compareBy { it.partIndex }
                    ),
                )
            }
    }

    private fun resolveRef(request: ExecuteRequest) =
        if (request.promptRefId != null) {
            promptRefRepository.findById(request.promptRefId)
                .orElseThrow { PromptRefNotFoundException(request.promptRefId) }
        } else {
            null
        }

    private fun resolveInstructions(
        request: ExecuteRequest,
        ref: PromptRef?,
    ): String? = when {
        ref != null -> ref.instructions
        !request.rawInstructionsText.isNullOrBlank() -> request.rawInstructionsText
        else -> null
    }

    private fun buildMessages(
        ref: PromptRef?,
        requestInputItems: List<ExecuteInputItem>,
    ): List<RenderedInput> {
        val messages = mutableListOf<RenderedInput>()

        if (ref != null) {
            val additionalItems = additionalInputItemRepository.findByPromptRefIdOrderByPositionAsc(ref.id)
            additionalItems.forEach { item ->
                messages.add(RenderedInput(role = item.role, message = item.message))
            }
            return messages
        }

        requestInputItems.forEach { item ->
            messages.add(RenderedInput(role = item.role, message = item.message))
        }

        return messages
    }

    private fun validateVendorSpecificInput(
        vendorName: String,
        messages: List<RenderedInput>,
    ) {
        if (vendorName.equals("google", ignoreCase = true)) {
            if (messages.isEmpty()) {
                throw GoogleInstructionsOnlyNotSupportedException()
            }
            if (messages.any { it.role == InputRole.SYSTEM || it.role == InputRole.DEVELOPER }) {
                throw GoogleSystemOrDeveloperInputNotSupportedException()
            }
            return
        }

        if (vendorName.equals("xai", ignoreCase = true) || vendorName.equals("grok", ignoreCase = true)) {
            if (messages.isEmpty()) {
                throw XaiInstructionsOnlyNotSupportedException()
            }
        }
    }

    private fun resolveOptions(workspaceId: UUID, overrideOptions: ExecuteOverrideOptions?): ResolvedOptions {
        val defaults = defaultLlmCallOptionsRepository.findByWorkspaceId(workspaceId)
        return ResolvedOptions(
            temperature = overrideOptions?.temperature ?: defaults?.temperature,
            topP = overrideOptions?.topP ?: defaults?.topP,
            topK = overrideOptions?.topK ?: defaults?.topK,
            includeThoughts = overrideOptions?.includeThoughts ?: defaults?.includeThoughts,
            maxTokens = overrideOptions?.maxTokens ?: defaults?.maxTokens,
            textFormat = overrideOptions?.textFormat ?: defaults?.textFormat,
            effort = overrideOptions?.effort ?: defaults?.effort,
            verbosity = overrideOptions?.verbosity ?: defaults?.verbosity,
            summary = overrideOptions?.summary ?: defaults?.summary,
        )
    }

    private fun elapsedMs(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private fun formatLlmError(ex: Throwable): String {
        val xaiException = findXaiApiException(ex)
        if (xaiException != null) {
            return buildString {
                append("status=")
                append(xaiException.statusCode)
                append(", message=")
                append(xaiException.message)
                append(", body=")
                append(xaiException.body)
            }
        }

        val serviceException = findOpenAIServiceException(ex) //TODO 나중에 라이브러리 안쪽으로 옮겨야 함.
        if (serviceException != null) {
            return buildString {
                append("status=")
                append(serviceException.statusCode())
                serviceException.code().ifPresent {
                    append(", code=")
                    append(it)
                }
                serviceException.type().ifPresent {
                    append(", type=")
                    append(it)
                }
                serviceException.param().ifPresent {
                    append(", param=")
                    append(it)
                }
                append(", message=")
                append(serviceException.message)
                append(", body=")
                append(serviceException.body())
            }
        }

        return ex.stackTraceToString()
    }

    private fun findXaiApiException(ex: Throwable): XaiApiException? {
        var current: Throwable? = ex
        while (current != null) {
            if (current is XaiApiException) {
                return current
            }
            current = current.cause
        }
        return null
    }

    private fun findOpenAIServiceException(ex: Throwable): OpenAIServiceException? {
        var current: Throwable? = ex
        while (current != null) {
            if (current is OpenAIServiceException) {
                return current
            }
            current = current.cause
        }
        return null
    }

    data class RenderedInput(
        val role: InputRole,
        val message: String,
    )

    private data class ResolvedOptions(
        val temperature: Double?,
        val topP: Double?,
        val topK: Double?,
        val includeThoughts: Boolean?,
        val maxTokens: Long?,
        val textFormat: String?,
        val effort: ReasoningEffortType?,
        val verbosity: VerbosityType?,
        val summary: SummaryType?,
    ) {
        fun toJson(objectMapper: ObjectMapper): String? {
            val baseNode = objectMapper.createObjectNode()
            temperature?.let { baseNode.put("temperature", it) }
            topP?.let { baseNode.put("topP", it) }
            topK?.let { baseNode.put("topK", it) }
            includeThoughts?.let { baseNode.put("includeThoughts", it) }
            maxTokens?.let { baseNode.put("maxTokens", it) }
            textFormat?.let { baseNode.put("textFormat", it) }
            effort?.let { baseNode.put("effort", it.name) }
            verbosity?.let { baseNode.put("verbosity", it.name) }
            summary?.let { baseNode.put("summary", it.name) }
            return if (baseNode.size() == 0) null else objectMapper.writeValueAsString(baseNode)
        }

        fun toStreamOptions() = LlmStreamOptions(
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

        fun filterByModelType(modelType: LlmModelType) =
            when (modelType) {
                LlmModelType.REASONING -> copy(
//                    maxTokens = null,
                    temperature = null,
                    topP = null,
                    textFormat = null,
                    verbosity = null,
                )
                LlmModelType.STANDARD -> copy(
                    effort = null,
                    summary = null,
                )
                LlmModelType.UNKNOWN -> copy(
                    effort = null,
                    summary = null,
                )
            }
    }
}

fun InputRole.toMessageRole() = when (this) {
    InputRole.USER -> LlmMessageInputItem.Role.USER
    InputRole.ASSISTANT -> LlmMessageInputItem.Role.ASSISTANT
    InputRole.DEVELOPER -> LlmMessageInputItem.Role.DEVELOPER
    InputRole.SYSTEM -> LlmMessageInputItem.Role.SYSTEM
}
