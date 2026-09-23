package malibu.llm.streamclient.xai

import com.openai.models.responses.*
import malibu.llm.streamclient.LlmFileSearchTool
import malibu.llm.streamclient.LlmMessageInputItem
import malibu.llm.streamclient.LlmStreamClient
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.streamclient.LlmStreamRequest
import malibu.llm.streamclient.LlmToolSpec
import malibu.llm.streamclient.PendingFunctionCallContract
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.StepEmission
import malibu.llm.streamclient.StreamSession
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.ToolCallback
import malibu.llm.streamclient.ToolDefinition
import malibu.llm.streamclient.ToolDefinitions
import malibu.llm.streamclient.effectiveMaxBufferedEvents
import malibu.llm.streamclient.exception.InputEmptyException
import malibu.llm.streamclient.exception.ToolArgumentsParseException
import malibu.llm.streamclient.exception.UnsupportedInputTypeException
import malibu.llm.streamclient.exception.UnsupportedToolException
import malibu.llm.streamclient.limitBufferedEvents
import malibu.llm.streamclient.mergeToolSpecs
import mu.KotlinLogging
import org.reactivestreams.Publisher
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class XaiLlmStreamClient(
    private val rawClient: XaiResponsesStreamClient,
    private val defaultRequestSpec: LlmStreamRequest,
    private val jsonMapper: JsonMapper,
) : LlmStreamClient {
    private val logger = KotlinLogging.logger {}

    override val vendorName: String = "xai"

    override fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent> {
        val effective = mergeSpecs(defaultRequestSpec, requestSpec)

        return Flux.create<LlmStreamEvent>({ sink ->
            val cancelled = AtomicBoolean(false)
            val currentSubscription = AtomicReference<Disposable?>(null)
            val context = ConversationContext(
                lastResponseId = effective.previousResponseId,
            )
            val session = StreamSession(sink)

            if (effective.inputs.isEmpty()) {
                sink.error(InputEmptyException())
                return@create
            }

            sink.onCancel {
                cancelled.set(true)
                currentSubscription.getAndSet(null)?.dispose()
            }
            sink.onDispose {
                cancelled.set(true)
                currentSubscription.getAndSet(null)?.dispose()
            }

            executeStep(
                spec = effective,
                inputItems = effective.inputs,
                context = context,
                session = session,
                cancelled = cancelled,
                currentSubscription = currentSubscription,
                sink = sink,
            )
        }, FluxSink.OverflowStrategy.ERROR)
            .limitBufferedEvents(effective.maxBufferedEvents)
    }

    private fun executeStep(
        spec: EffectiveRequest,
        inputItems: List<XaiInputItem>,
        context: ConversationContext,
        session: StreamSession,
        cancelled: AtomicBoolean,
        currentSubscription: AtomicReference<Disposable?>,
        sink: FluxSink<LlmStreamEvent>,
    ) {
        if (cancelled.get() || sink.isCancelled) {
            return
        }

        val pendingNextInputs = mutableListOf<XaiInputItem>()
        val hadReturnDirect = AtomicBoolean(false)
        val roundCompleted = AtomicReference<LlmStreamEvent.ResponseCompleted?>()
        val openItemId = AtomicReference<String?>()
        val openItemIndex = AtomicReference<Long?>()

        val request = XaiResponsesStreamRequest(
            model = spec.model,
            input = inputItems,
            instructions = spec.instructions,
            store = spec.store,
            temperature = spec.temperature,
            topP = spec.topP,
            maxOutputTokens = spec.maxTokens,
            include = spec.include,
            reasoning = spec.reasoning,
            text = spec.text,
            tools = spec.toolsPayload,
            toolChoice = spec.toolChoice,
            previousResponseId = context.lastResponseId ?: spec.previousResponseId,
        )

        val source = rawClient.stream(request)
            .limitBufferedEvents(spec.maxBufferedEvents)
            .flatMapIterable { rawEvent ->
                mapRawEventToEmissions(
                    rawEvent = rawEvent,
                    context = context,
                    session = session,
                    openItemId = openItemId,
                    openItemIndex = openItemIndex,
                    roundCompleted = roundCompleted,
                )
            }
            .concatWith(Mono.fromCallable { StepEmission.EndOfRound(roundCompleted.get()) })

        val subscription = source
            .concatMap<Any> { unit ->
                if (cancelled.get() || sink.isCancelled) {
                    return@concatMap Mono.empty<Any>()
                }
                when (unit) {
                    is StepEmission.PassThrough -> {
                        if (hadReturnDirect.get()) return@concatMap Mono.empty<Any>()
                        val event = unit.event
                        if (event is LlmStreamEvent.ResponseCreated) {
                            session.bufferResponseCreated(event)
                        } else {
                            session.emitExternal(event)
                        }
                        Mono.empty<Any>()
                    }
                    is StepEmission.ToolInvocation -> {
                        if (hadReturnDirect.get()) return@concatMap Mono.empty<Any>()
                        handleToolInline(
                            call = unit.call as PendingFunctionCall,
                            handlers = spec.toolHandlers,
                            session = session,
                            pendingNextInputs = pendingNextInputs,
                            hadReturnDirect = hadReturnDirect,
                        ).cast(Any::class.java)
                    }
                    is StepEmission.EndOfRound -> {
                        if (hadReturnDirect.get()) {
                            session.finalizeSilently()
                        } else if (pendingNextInputs.isEmpty()) {
                            session.finalize(unit.completed)
                        } else {
                            session.absorbRoundCompleted(unit.completed)
                            val next = pendingNextInputs.toList()
                            executeStep(
                                spec = spec,
                                inputItems = next,
                                context = context,
                                session = session,
                                cancelled = cancelled,
                                currentSubscription = currentSubscription,
                                sink = sink,
                            )
                        }
                        Mono.empty<Any>()
                    }
                }
            }
            .subscribe(
                {},
                { error ->
                    if (!cancelled.get() && !sink.isCancelled) {
                        logger.warn(error) { "xAI stream error" }
                        session.error(error)
                    }
                },
                { /* completion handled inside EndOfRound branch */ },
            )

        currentSubscription.getAndSet(subscription)?.dispose()
    }

    private fun mapRawEventToEmissions(
        rawEvent: XaiRawStreamEvent,
        context: ConversationContext,
        session: StreamSession,
        openItemId: AtomicReference<String?>,
        openItemIndex: AtomicReference<Long?>,
        roundCompleted: AtomicReference<LlmStreamEvent.ResponseCompleted?>,
    ): List<StepEmission> {
        val node = jsonMapper.readTree(rawEvent.payload)
        return when (rawEvent.type) {
            "response.created" -> {
                val response = node.path("response")
                val responseId = response.textOrNull("id") ?: node.textOrNull("response_id")
                if (responseId.isNullOrBlank()) {
                    emptyList()
                } else {
                    context.lastResponseId = responseId
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ResponseCreated(
                                id = responseId,
                                sequence = session.nextSequence.getAndIncrement(),
                                model = response.textOrNull("model"),
                            )
                        )
                    )
                }
            }

            "response.output_item.added" -> {
                val item = node.path("item")
                when (item.path("type").asString()) {
                    "message" -> {
                        val nativeItemId = item.path("id").asString()
                        val assignedIndex = session.nextItemIndex.getAndIncrement()
                        openItemId.set(nativeItemId)
                        openItemIndex.set(assignedIndex)
                        listOf(
                            StepEmission.PassThrough(
                                LlmStreamEvent.ResponseMessageAdded(
                                    itemId = nativeItemId,
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = assignedIndex,
                                )
                            )
                        )
                    }
                    "reasoning" -> {
                        val nativeItemId = item.path("id").asString()
                        val assignedIndex = session.nextItemIndex.getAndIncrement()
                        openItemId.set(nativeItemId)
                        openItemIndex.set(assignedIndex)
                        listOf(
                            StepEmission.PassThrough(
                                LlmStreamEvent.ResponseReasoningAdded(
                                    itemId = nativeItemId,
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = assignedIndex,
                                )
                            )
                        )
                    }
                    else -> emptyList()
                }
            }

            "response.content_part.added" -> {
                val part = node.path("part")
                val itemId = node.path("item_id").asString()
                val itemIndex = openItemIndex.get()
                when (part.path("type").asString()) {
                    "output_text" -> listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.MessageContentPartAdded(
                                itemId = itemId,
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex,
                                partIndex = node.path("content_index").asLong(),
                            )
                        )
                    )
                    "summary_text" -> listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ReasoningSummaryPartAdded(
                                itemId = itemId,
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex ?: 0L,
                                partIndex = node.path("summary_index").asLong(),
                            )
                        )
                    )
                    else -> emptyList()
                }
            }

            "response.output_text.delta" -> {
                val delta = node.path("delta").asString("")
                if (delta.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.MessageDelta(
                                itemId = node.path("item_id").asString(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = openItemIndex.get(),
                                partIndex = node.path("content_index").asLong(),
                                delta = delta,
                            )
                        )
                    )
                }
            }

            "response.reasoning_summary_text.delta" -> {
                val delta = node.path("delta").asString("")
                if (delta.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ReasoningDelta(
                                itemId = node.path("item_id").asString(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = openItemIndex.get() ?: 0L,
                                partIndex = node.path("summary_index").asLong(),
                                delta = delta,
                            )
                        )
                    )
                }
            }

            "response.content_part.done" -> {
                val part = node.path("part")
                val itemId = node.path("item_id").asString()
                val itemIndex = openItemIndex.get()
                when (part.path("type").asString()) {
                    "output_text" -> listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.MessageContentPartDone(
                                itemId = itemId,
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex,
                                partIndex = node.path("content_index").asLong(),
                                text = part.textOrNull("text"),
                            )
                        )
                    )
                    "summary_text" -> listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ReasoningSummaryPartDone(
                                itemId = itemId,
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex ?: 0L,
                                partIndex = node.path("summary_index").asLong(),
                                text = part.textOrNull("text") ?: "",
                            )
                        )
                    )
                    else -> emptyList()
                }
            }

            "response.output_item.done" -> {
                val item = node.path("item")
                when (item.path("type").asString()) {
                    "message" -> {
                        val itemIndex = openItemIndex.get() ?: session.nextItemIndex.getAndIncrement()
                        openItemId.set(null)
                        openItemIndex.set(null)
                        listOf(
                            StepEmission.PassThrough(
                                LlmStreamEvent.ResponseMessageDone(
                                    itemId = item.path("id").asString(),
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = itemIndex,
                                )
                            )
                        )
                    }
                    "reasoning" -> {
                        val itemIndex = openItemIndex.get() ?: session.nextItemIndex.getAndIncrement()
                        openItemId.set(null)
                        openItemIndex.set(null)
                        listOf(
                            StepEmission.PassThrough(
                                LlmStreamEvent.ResponseReasoningDone(
                                    itemId = item.path("id").asString(),
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = itemIndex,
                                )
                            )
                        )
                    }
                    "function_call" -> {
                        val functionCall = PendingFunctionCall(
                            callId = item.textOrNull("call_id") ?: item.path("id").asString(),
                            toolName = item.path("name").asString(),
                            argumentsJson = item.textOrNull("arguments") ?: "{}",
                            requestSequence = session.nextSequence.getAndIncrement(),
                        )
                        listOf(StepEmission.ToolInvocation(functionCall))
                    }
                    else -> emptyList()
                }
            }

            "response.completed" -> {
                val response = node.path("response")
                val responseId = response.textOrNull("id") ?: throw RuntimeException("responseId 를 찾을 수 없습니다.!")
                context.lastResponseId = responseId
                val usage = response.path("usage")
                roundCompleted.set(
                    LlmStreamEvent.ResponseCompleted(
                        id = responseId,
                        sequence = session.nextSequence.getAndIncrement(),
                        inputTokens = usage.path("input_tokens").asLongOrNull(),
                        cachedTokens = usage.path("input_tokens_details").path("cached_tokens").asLongOrNull(),
                        outputTokens = usage.path("output_tokens").asLongOrNull(),
                        reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens").asLongOrNull(),
                        totalTokens = usage.path("total_tokens").asLongOrNull(),
                    )
                )
                emptyList()
            }

            "response.failed" -> {
                val error = node.path("response").path("error")
                throw XaiApiException(
                    statusCode = 500,
                    body = rawEvent.payload,
                    message = error.textOrNull("message") ?: "xAI response failed",
                )
            }

            "error" -> {
                val error = node.path("error")
                throw XaiApiException(
                    statusCode = error.path("status_code").asInt(500),
                    body = rawEvent.payload,
                    message = error.textOrNull("message") ?: "xAI stream error",
                )
            }

            else -> emptyList()
        }
    }

    private fun handleToolInline(
        call: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
        session: StreamSession,
        pendingNextInputs: MutableList<XaiInputItem>,
        hadReturnDirect: AtomicBoolean,
    ): Mono<Unit> {
        return invokeTool(call, handlers)
            .doOnNext { result ->
                emitFunctionCallDone(session, result)
                if (result.directResult != null) {
                    hadReturnDirect.set(true)
                    emitReturnDirectMessage(session, result.directResult)
                } else if (result.responseInput != null) {
                    pendingNextInputs.add(result.responseInput)
                }
            }
            .onErrorResume { error ->
                emitFunctionCallFailed(session, call, error)
                Mono.error(error)
            }
            .then(Mono.empty())
    }

    private fun invokeTool(
        request: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
    ): Mono<ToolInvocationResult> {
        val handler = handlers[request.toolName]
            ?: return Mono.error(IllegalStateException("No tool callback registered for ${request.toolName}"))

        val parsedArguments: Any = try {
            jsonMapper.readValue(request.argumentsJson, handler.inputJavaType)
        } catch (ex: Exception) {
            return Mono.error(ToolArgumentsParseException(request.toolName, ex))
        }

        val publisher: Publisher<Any> = try {
            @Suppress("UNCHECKED_CAST")
            handler.callback.callback(parsedArguments) as Publisher<Any>
        } catch (ex: Exception) {
            return Mono.error(ex)
        }

        return Flux.from(publisher)
            .switchIfEmpty(Flux.error(IllegalStateException("Tool ${request.toolName} returned no result")))
            .single()
            .map { result ->
                val serializedOutput = serializeToolResult(result)
                if (handler.callback.returnDirect) {
                    ToolInvocationResult(
                        request = request,
                        responseInput = null,
                        directResult = DirectToolResult(
                            callId = request.callId,
                            output = serializedOutput,
                        ),
                        output = serializedOutput,
                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT,
                    )
                } else {
                    ToolInvocationResult(
                        request = request,
                        responseInput = XaiInputItem.FunctionCallOutput(
                            callId = request.callId,
                            output = serializedOutput,
                        ),
                        directResult = null,
                        output = serializedOutput,
                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL,
                    )
                }
            }
    }

    private fun emitFunctionCallDone(
        session: StreamSession,
        result: ToolInvocationResult,
    ) {
        if (session.isCancelled) return
        session.emitExternal(
            LlmStreamEvent.FunctionCallObserved(
                callId = result.request.callId,
                toolName = result.request.toolName,
                sequence = result.request.requestSequence,
                argumentsJson = result.request.argumentsJson,
                output = result.output,
                delivery = result.delivery,
            )
        )
    }

    private fun emitFunctionCallFailed(
        session: StreamSession,
        request: PendingFunctionCall,
        error: Throwable,
    ) {
        if (session.isCancelled) return
        session.emitExternal(
            LlmStreamEvent.FunctionCallObserved(
                callId = request.callId,
                toolName = request.toolName,
                sequence = request.requestSequence,
                argumentsJson = request.argumentsJson,
                errorMessage = error.message ?: error::class.simpleName,
            )
        )
    }

    private fun emitReturnDirectMessage(
        session: StreamSession,
        result: DirectToolResult,
    ) {
        if (session.isCancelled) return
        val itemId = "return-direct-${result.callId}-${UUID.randomUUID()}"
        val itemIndex = session.nextItemIndex.getAndIncrement()
        session.emitExternal(
            LlmStreamEvent.ResponseMessageAdded(
                itemId = itemId,
                sequence = session.nextSequence.getAndIncrement(),
                itemIndex = itemIndex,
            )
        )
        session.emitExternal(
            LlmStreamEvent.MessageContentPartAdded(
                itemId = itemId,
                sequence = session.nextSequence.getAndIncrement(),
                itemIndex = itemIndex,
                partIndex = 0L,
            )
        )
        session.emitExternal(
            LlmStreamEvent.MessageDelta(
                itemId = itemId,
                sequence = session.nextSequence.getAndIncrement(),
                itemIndex = itemIndex,
                partIndex = 0L,
                delta = result.output,
            )
        )
        session.emitExternal(
            LlmStreamEvent.MessageContentPartDone(
                itemId = itemId,
                sequence = session.nextSequence.getAndIncrement(),
                itemIndex = itemIndex,
                partIndex = 0L,
                text = result.output,
            )
        )
        session.emitExternal(
            LlmStreamEvent.ResponseMessageDone(
                itemId = itemId,
                sequence = session.nextSequence.getAndIncrement(),
                itemIndex = itemIndex,
            )
        )
    }

    private fun serializeToolResult(result: Any?): String {
        return when (result) {
            null -> "null"
            is String -> result
            is Number, is Boolean -> result.toString()
            else -> jsonMapper.writeValueAsString(result)
        }
    }

    private fun mergeSpecs(base: LlmStreamRequest, override: LlmStreamRequest): EffectiveRequest {
        val mergedTools = mergeToolSpecs(base.tools, override.tools)
        mergedTools.requireSupportedTools("xai")
        val functionTools = mergedTools.filterIsInstance<ToolCallback<*, *>>()
        val fileSearchTools = mergedTools.filterIsInstance<LlmFileSearchTool>()
        @Suppress("UNCHECKED_CAST")
        val toolHandlers = functionTools.associate { callback ->
            callback.name to ToolHandler(
                callback = callback as ToolCallback<Any, Any?>,
                inputJavaType = callback.inputType.java,
            )
        }

        return EffectiveRequest(
            conversationId = override.conversationId ?: base.conversationId,
            previousResponseId = override.previousResponseId ?: base.previousResponseId,
            instructions = override.instructions ?: base.instructions,
            inputs = (override.inputs ?: base.inputs ?: emptyList()).map { input ->
                when (input) {
                    is LlmMessageInputItem -> XaiInputItem.Message(
                        role = input.role.name.lowercase(),
                        content = input.content,
                    )
                    else -> throw UnsupportedInputTypeException(input.type.name)
                }
            },
            model = override.modelKey ?: base.modelKey ?: DEFAULT_MODEL,
            store = override.options?.store ?: base.options?.store,
            temperature = override.options?.temperature ?: base.options?.temperature,
            topP = override.options?.topP ?: base.options?.topP,
            maxTokens = override.options?.maxTokens ?: base.options?.maxTokens,
            include = mapInclude(override.options?.includeThoughts ?: base.options?.includeThoughts),
            reasoning = mapReasoning(
                effort = override.options?.effort ?: base.options?.effort,
                summary = override.options?.summary ?: base.options?.summary,
            ),
            text = mapTextResponse(override.options?.textFormat ?: base.options?.textFormat),
            toolChoice = mapToolChoice(override.toolChoice ?: base.toolChoice),
            toolHandlers = toolHandlers,
            toolsPayload = functionTools.map { callback -> callback.toXaiTool(ToolDefinitions.from(callback)) } +
                fileSearchTools.map(::toXaiFileSearchTool),
            maxBufferedEvents = effectiveMaxBufferedEvents(override.options, base.options),
        )
    }

    private fun List<LlmToolSpec>.requireSupportedTools(provider: String) {
        val unsupported = filterNot { it is ToolCallback<*, *> || it is LlmFileSearchTool }
        if (unsupported.isNotEmpty()) {
            throw UnsupportedToolException(provider, unsupported.map { it.toolType })
        }
    }

    private fun mapInclude(includeThoughts: Boolean?): List<String> {
        return if (includeThoughts == true) {
            listOf(REASONING_ENCRYPTED_CONTENT_INCLUDE)
        } else {
            emptyList()
        }
    }

    private fun mapReasoning(
        effort: ReasoningEffortType?,
        summary: SummaryType?,
    ): XaiReasoningConfiguration? {
        val mappedEffort = effort?.let {
            when (it) {
                ReasoningEffortType.NONE -> "none"
                ReasoningEffortType.MINIMAL -> "low"
                ReasoningEffortType.LOW -> "low"
                ReasoningEffortType.MEDIUM -> "medium"
                ReasoningEffortType.HIGH -> "high"
                ReasoningEffortType.XHIGH -> "xhigh"
            }
        }
        val mappedSummary = summary?.let {
            when (it) {
                SummaryType.NONE -> null
                SummaryType.AUTO -> "auto"
                SummaryType.CONCISE -> "concise"
                SummaryType.DETAILED -> "detailed"
            }
        }

        return if (mappedEffort == null && mappedSummary == null) {
            null
        } else {
            XaiReasoningConfiguration(
                effort = mappedEffort,
                summary = mappedSummary,
            )
        }
    }

    private fun mapTextResponse(textFormat: String?): XaiTextResponseConfiguration? {
        val format = textFormat?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return XaiTextResponseConfiguration(
            format = mapOf("type" to format),
        )
    }

    private fun mapToolChoice(choice: ResponseCreateParams.ToolChoice?): XaiToolChoice? {
        if (choice == null) {
            return null
        }
        return choice.accept(
            object : ResponseCreateParams.ToolChoice.Visitor<XaiToolChoice?> {
                override fun visitOptions(options: ToolChoiceOptions): XaiToolChoice {
                    return when (options.value()) {
                        ToolChoiceOptions.Value.NONE -> XaiToolChoice.None
                        ToolChoiceOptions.Value.AUTO -> XaiToolChoice.Auto
                        ToolChoiceOptions.Value.REQUIRED -> XaiToolChoice.Required
                        ToolChoiceOptions.Value._UNKNOWN -> XaiToolChoice.Auto
                    }
                }

                override fun visitAllowed(allowed: ToolChoiceAllowed): XaiToolChoice {
                    return XaiToolChoice.Required
                }

                override fun visitTypes(types: ToolChoiceTypes): XaiToolChoice {
                    return XaiToolChoice.Required
                }

                override fun visitFunction(function: ToolChoiceFunction): XaiToolChoice {
                    return XaiToolChoice.Function(function.name())
                }

                override fun visitMcp(mcp: ToolChoiceMcp): XaiToolChoice {
                    return XaiToolChoice.Required
                }

                override fun visitCustom(custom: ToolChoiceCustom): XaiToolChoice {
                    return XaiToolChoice.Required
                }

                override fun visitApplyPatch(applyPatch: ToolChoiceApplyPatch): XaiToolChoice {
                    return XaiToolChoice.Required
                }

                override fun visitShell(shell: ToolChoiceShell): XaiToolChoice {
                    return XaiToolChoice.Required
                }
            }
        )
    }

    private fun ToolCallback<*, *>.toXaiTool(definition: ToolDefinition): XaiFunctionTool {
        return XaiFunctionTool(
            name = name,
            description = description.ifBlank { null },
            parameters = definition.inputSchema,
        )
    }

    private fun toXaiFileSearchTool(tool: LlmFileSearchTool): XaiFileSearchTool {
        return XaiFileSearchTool(
            vectorStoreIds = tool.vectorStoreIds,
            maxNumResults = tool.maxNumResults,
        )
    }

    private fun JsonNode.asLongOrNull(): Long? {
        return if (isMissingNode || isNull) {
            null
        } else {
            asLong()
        }
    }

    private fun JsonNode.textOrNull(fieldName: String): String? {
        val value = path(fieldName)
        return if (value.isMissingNode || value.isNull) {
            null
        } else {
            value.asText()
        }
    }

    private data class EffectiveRequest(
        val conversationId: String?,
        val previousResponseId: String?,
        val instructions: String?,
        val inputs: List<XaiInputItem>,
        val model: String,
        val store: Boolean?,
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Long?,
        val include: List<String>,
        val reasoning: XaiReasoningConfiguration?,
        val text: XaiTextResponseConfiguration?,
        val toolChoice: XaiToolChoice?,
        val toolHandlers: Map<String, ToolHandler>,
        val toolsPayload: List<XaiTool>,
        val maxBufferedEvents: Int,
    )

    private data class ToolHandler(
        val callback: ToolCallback<Any, Any?>,
        val inputJavaType: Class<*>,
    )

    private data class PendingFunctionCall(
        override val callId: String,
        override val toolName: String,
        override val argumentsJson: String,
        override val requestSequence: Long? = null,
    ) : PendingFunctionCallContract

    private data class ToolInvocationResult(
        val request: PendingFunctionCall,
        val responseInput: XaiInputItem?,
        val directResult: DirectToolResult?,
        val output: String,
        val delivery: LlmStreamEvent.FunctionCallObserved.Delivery,
    )

    private data class DirectToolResult(
        val callId: String,
        val output: String,
    )

    private data class ConversationContext(
        var lastResponseId: String? = null,
    )

    private companion object {
        private const val DEFAULT_MODEL = "grok-4-1-fast-non-reasoning"
        private const val REASONING_ENCRYPTED_CONTENT_INCLUDE = "reasoning.encrypted_content"
    }
}
