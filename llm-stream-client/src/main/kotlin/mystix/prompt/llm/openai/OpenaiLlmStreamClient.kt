package mystix.prompt.llm.openai

import com.openai.client.OpenAIClientAsync
import com.openai.core.JsonValue
import com.openai.core.http.AsyncStreamResponse
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.ResponseFormatText
import com.openai.models.responses.*
import com.openai.services.async.ResponseServiceAsync
import mu.KotlinLogging
import mystix.prompt.llm.*
import mystix.prompt.llm.exception.InputEmptyException
import mystix.prompt.llm.exception.ToolArgumentsParseException
import mystix.prompt.llm.exception.UnsupportedInputTypeException
import mystix.prompt.llm.exception.UnsupportedSummaryTypeException
import mystix.prompt.llm.exception.UnsupportedTextFormatException
import mystix.prompt.llm.schema.OpenAiToolSchemaNormalizer
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrNull

/**
 * openai async client 를 좀더 다루기 쉽게 래핑.
 *
 * function call 관련 처리 등을 자체적으로 처리해준다. 라운드 경계는 컨슈머에게 보이지 않고
 * 하나의 응답 lifecycle 처럼 노출된다.
 */
class OpenaiLlmStreamClient(
    private val openAIClient: OpenAIClientAsync,
    /** 기본 요청 스펙. */
    private val defaultRequestSpec: LlmStreamRequest,
    private val jsonMapper: JsonMapper,
): LlmStreamClient {
    private val logger = KotlinLogging.logger {}

    private val responses: ResponseServiceAsync by lazy { openAIClient.responses() }

    override val vendorName = "openai"

    override fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent> {
        val effective = mergeSpecs(defaultRequestSpec, requestSpec)

        return Flux.create<LlmStreamEvent>({ sink ->
            val context = ConversationContext(
                initialConversationId = effective.conversationId,
                initialPreviousResponseId = effective.previousResponseId,
            )
            val streamingState = StreamingState()
            val session = StreamSession(sink)
            val initialInputs = convertInputs(effective.inputs)

            if (effective.instructions == null && initialInputs.isEmpty()) {
                sink.error(InputEmptyException())
                return@create
            }

            sink.onDispose { streamingState.close() }
            executeStep(
                spec = effective,
                context = context,
                inputItems = initialInputs,
                state = streamingState,
                session = session,
                sink = sink,
            )
        }, FluxSink.OverflowStrategy.ERROR)
            .limitBufferedEvents(effective.maxBufferedEvents)
    }

    private fun executeStep(
        spec: EffectiveRequest,
        context: ConversationContext,
        inputItems: List<ResponseInputItem>,
        state: StreamingState,
        session: StreamSession,
        sink: FluxSink<LlmStreamEvent>,
    ) {
        if (sink.isCancelled) {
            return
        }

        val params = buildParams(spec, context, inputItems)
        val response = responses.createStreaming(params)
        state.replace(response)

        val pendingNextInputs = mutableListOf<ResponseInputItem>()
        val hadReturnDirect = AtomicBoolean(false)
        val roundCompleted = AtomicReference<LlmStreamEvent.ResponseCompleted?>()
        // OpenAI native outputIndex -> 우리가 발급한 itemIndex 매핑.
        val itemIndexMapping = mutableMapOf<Long, Long>()

        // AsyncStreamResponse 를 Flux 로 어댑팅.
        val sourceFlux = Flux.create<ResponseStreamEvent>({ eventSink ->
            response.subscribe(object : AsyncStreamResponse.Handler<ResponseStreamEvent> {
                override fun onNext(value: ResponseStreamEvent) {
                    if (eventSink.isCancelled) {
                        state.clear(response)
                        response.close()
                        return
                    }
                    eventSink.next(value)
                }

                override fun onComplete(error: Optional<Throwable>) {
                    state.clear(response)
                    if (error.isPresent) {
                        eventSink.error(error.get())
                    } else {
                        eventSink.complete()
                    }
                }
            })
            eventSink.onCancel { response.close() }
        }, FluxSink.OverflowStrategy.ERROR)
            .limitBufferedEvents(spec.maxBufferedEvents)

        val emissionFlux: Flux<StepEmission> = sourceFlux
            .flatMapIterable { event ->
                mapEventToEmissions(
                    event = event,
                    context = context,
                    session = session,
                    itemIndexMapping = itemIndexMapping,
                    roundCompleted = roundCompleted,
                )
            }
            .concatWith(Mono.fromCallable { StepEmission.EndOfRound(roundCompleted.get()) })

        emissionFlux
            .concatMap<Any> { unit ->
                if (sink.isCancelled) {
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
                            executeStep(spec, context, next, state, session, sink)
                        }
                        Mono.empty<Any>()
                    }
                }
            }
            .subscribe(
                {},
                { error ->
                    if (!sink.isCancelled) {
                        logger.warn(error) { "openai stream error" }
                        session.error(error)
                    }
                },
                { /* completion handled inside EndOfRound branch */ },
            )
    }

    private fun mapEventToEmissions(
        event: ResponseStreamEvent,
        context: ConversationContext,
        session: StreamSession,
        itemIndexMapping: MutableMap<Long, Long>,
        roundCompleted: AtomicReference<LlmStreamEvent.ResponseCompleted?>,
    ): List<StepEmission> {
        if (logger.isTraceEnabled) {
            logger.trace { "mapEventToEmissions: $event" }
        }

        // message text delta
        if (event.isOutputTextDelta()) {
            val delta = event.asOutputTextDelta()
            if (delta.delta().isEmpty()) return emptyList()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.MessageDelta(
                        itemId = delta.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, delta.outputIndex()),
                        partIndex = delta.contentIndex(),
                        delta = delta.delta(),
                    )
                )
            )
        }
        if (event.isContentPartAdded()) {
            val added = event.asContentPartAdded()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.MessageContentPartAdded(
                        itemId = added.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, added.outputIndex()),
                        partIndex = added.contentIndex(),
                    )
                )
            )
        }
        if (event.isContentPartDone()) {
            val done = event.asContentPartDone()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.MessageContentPartDone(
                        itemId = done.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, done.outputIndex()),
                        partIndex = done.contentIndex(),
                        text = done.part().outputText().map { it.text() }.getOrNull(),
                    )
                )
            )
        }

        // reasoning summary
        if (event.isReasoningSummaryTextDelta()) {
            val delta = event.asReasoningSummaryTextDelta()
            if (delta.delta().isEmpty()) return emptyList()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.ReasoningDelta(
                        itemId = delta.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, delta.outputIndex()),
                        partIndex = delta.summaryIndex(),
                        delta = delta.delta(),
                    )
                )
            )
        }
        if (event.isReasoningSummaryPartAdded()) {
            val added = event.asReasoningSummaryPartAdded()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.ReasoningSummaryPartAdded(
                        itemId = added.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, added.outputIndex()),
                        partIndex = added.summaryIndex(),
                    )
                )
            )
        }
        if (event.isReasoningSummaryPartDone()) {
            val done = event.asReasoningSummaryPartDone()
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.ReasoningSummaryPartDone(
                        itemId = done.itemId(),
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = remappedItemIndex(itemIndexMapping, session, done.outputIndex()),
                        partIndex = done.summaryIndex(),
                        text = done.part().text(),
                    )
                )
            )
        }

        if (event.isCreated()) {
            val created = event.asCreated()
            context.updateFrom(created.response())
            val model = if (created.response().model().isChat()) {
                created.response().model().asChat().toString()
            } else if (created.response().model().isString()) {
                created.response().model().asString()
            } else {
                created.response().model().toString()
            }
            return listOf(
                StepEmission.PassThrough(
                    LlmStreamEvent.ResponseCreated(
                        id = created.response().id(),
                        sequence = session.nextSequence.getAndIncrement(),
                        model = model,
                    )
                )
            )
        }

        if (event.isOutputItemAdded()) {
            val itemAdded = event.asOutputItemAdded()
            val nativeIdx = itemAdded.outputIndex()
            return when {
                itemAdded.item().isMessage() -> {
                    val assignedIdx = remappedItemIndex(itemIndexMapping, session, nativeIdx)
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ResponseMessageAdded(
                                itemId = itemAdded.item().asMessage().id(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = assignedIdx,
                            )
                        )
                    )
                }
                itemAdded.item().isReasoning() -> {
                    val assignedIdx = remappedItemIndex(itemIndexMapping, session, nativeIdx)
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ResponseReasoningAdded(
                                itemId = itemAdded.item().asReasoning().id(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = assignedIdx,
                            )
                        )
                    )
                }
                else -> emptyList()
            }
        }

        if (event.isOutputItemDone()) {
            val itemDone = event.asOutputItemDone()
            val item = itemDone.item()

            return when {
                item.isFunctionCall() -> {
                    val fc = item.asFunctionCall()
                    if (logger.isDebugEnabled) {
                        logger.debug { "Function call requested: ${fc.name()} (callId=${fc.callId()})" }
                    }
                    listOf(
                        StepEmission.ToolInvocation(
                            PendingFunctionCall(
                                callId = fc.callId(),
                                toolName = fc.name(),
                                argumentsJson = fc.arguments(),
                                requestSequence = session.nextSequence.getAndIncrement(),
                            )
                        )
                    )
                }
                item.isMessage() -> {
                    val itemIndex = remappedItemIndex(itemIndexMapping, session, itemDone.outputIndex())
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ResponseMessageDone(
                                itemId = item.asMessage().id(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex,
                            )
                        )
                    )
                }
                item.isReasoning() -> {
                    val itemIndex = remappedItemIndex(itemIndexMapping, session, itemDone.outputIndex())
                    listOf(
                        StepEmission.PassThrough(
                            LlmStreamEvent.ResponseReasoningDone(
                                itemId = item.asReasoning().id(),
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex,
                            )
                        )
                    )
                }
                else -> emptyList()
            }
        }

        if (event.isCompleted()) {
            val completed = event.asCompleted()
            context.updateFrom(completed.response())
            roundCompleted.set(buildResponseCompletedEvent(completed, session))
            return emptyList()
        }

        if (event.isError()) {
            val error = event.asError()
            val code = error.code().orElse("unknown")
            val message = error.message()
            throw RuntimeException("OpenAI error ($code): $message")
        }

        if (event.isFailed()) {
            val responseError = event.asFailed().response().error()
            val code = responseError.map { it.code().toString() }.orElse("unknown")
            val message = responseError.map { it.message() }.orElse("unknown")
            throw IllegalStateException("Response failed ($code): $message")
        }

        if (event.isIncomplete()) {
            val incomplete = event.asIncomplete()
            throw IllegalStateException("Incomplete: $incomplete")
        }

        return emptyList()
    }

    /**
     * 네이티브 outputIndex 를 우리 식 itemIndex 로 매핑.
     * outputItemAdded 에서 이미 매핑이 추가되어 있어야 하지만, 누락 케이스(이전 SDK 등) 대비 fallback.
     */
    private fun remappedItemIndex(
        mapping: MutableMap<Long, Long>,
        session: StreamSession,
        nativeIndex: Long,
    ): Long {
        return mapping[nativeIndex] ?: run {
            val assigned = session.nextItemIndex.getAndIncrement()
            mapping[nativeIndex] = assigned
            assigned
        }
    }

    private fun buildResponseCompletedEvent(
        completed: ResponseCompletedEvent,
        session: StreamSession,
    ): LlmStreamEvent.ResponseCompleted? {
        val response = completed.response()
        val usage = response.usage().orElse(null) ?: return null
        val inputTokens = runCatching { usage.inputTokens() }.getOrNull()
        val cachedTokens = runCatching { usage.inputTokensDetails().cachedTokens() }.getOrNull()
        val outputTokens = runCatching { usage.outputTokens() }.getOrNull()
        val reasoningTokens = runCatching { usage.outputTokensDetails().reasoningTokens() }.getOrNull()
        val totalTokens = runCatching { usage.totalTokens() }.getOrNull()
        return LlmStreamEvent.ResponseCompleted(
            id = response.id(),
            sequence = session.nextSequence.getAndIncrement(),
            inputTokens = inputTokens,
            cachedTokens = cachedTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            totalTokens = totalTokens,
        )
    }

    private fun handleToolInline(
        call: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
        session: StreamSession,
        pendingNextInputs: MutableList<ResponseInputItem>,
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

        val publisher: Publisher<Any?> = try {
            @Suppress("UNCHECKED_CAST")
            handler.callback.callback(parsedArguments) as Publisher<Any?>
        } catch (ex: Exception) {
            return Mono.error(ex)
        }

        val safeFlux = Flux.create<Any> { sink ->
            publisher.subscribe(object : Subscriber<Any?> {
                private var subscription: Subscription? = null

                override fun onSubscribe(sub: Subscription) {
                    subscription = sub
                    sink.onCancel { sub.cancel() }
                    sub.request(Long.MAX_VALUE)
                }

                override fun onNext(value: Any?) {
                    if (value == null) {
                        subscription?.cancel()
                        sink.error(IllegalStateException("Tool ${request.toolName} returned null result"))
                    } else {
                        sink.next(value)
                    }
                }

                override fun onError(t: Throwable) {
                    sink.error(t)
                }

                override fun onComplete() {
                    sink.complete()
                }
            })
        }

        return safeFlux
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
                        responseInput = ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                .callId(request.callId)
                                .output(encodeToolResult(result))
                                .build()
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

    private fun serializeToolResult(result: Any?): String = when (result) {
        null -> "null"
        is String -> result
        is Number, is Boolean -> result.toString()
        else -> jsonMapper.writeValueAsString(result)
    }

    private fun encodeToolResult(result: Any?): ResponseInputItem.FunctionCallOutput.Output {
        return ResponseInputItem.FunctionCallOutput.Output.ofString(serializeToolResult(result))
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

    private fun buildParams(
        spec: EffectiveRequest,
        context: ConversationContext,
        inputItems: List<ResponseInputItem>,
    ): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(spec.model)

        spec.instructions?.let(builder::instructions)
        if (inputItems.isNotEmpty()) {
            builder.inputOfResponse(inputItems)
        }

        spec.maxOutputTokens?.let(builder::maxOutputTokens)
        spec.store?.let(builder::store)
        spec.temperature?.let(builder::temperature)
        spec.topP?.let(builder::topP)
        if (spec.include.isNotEmpty()) {
            builder.include(spec.include)
        }
        spec.reasoning?.let(builder::reasoning)
        spec.text?.let(builder::text)
        spec.toolChoice?.let(builder::toolChoice)

        if (spec.toolsPayload.isNotEmpty()) {
            builder.tools(spec.toolsPayload)
        }

        val previousResponseId = context.lastResponseId ?: spec.previousResponseId
        val conversationId = context.conversationId ?: spec.conversationId

        when {
            previousResponseId != null -> builder.previousResponseId(previousResponseId)
            conversationId != null -> builder.conversation(conversationId)
        }

        return builder.build()
    }

    private fun mergeSpecs(base: LlmStreamRequest, override: LlmStreamRequest): EffectiveRequest {
        val mergedReasoningEffort = (override.options?.effort ?: base.options?.effort)?.let { reasoning ->
            when (reasoning) {
                ReasoningEffortType.NONE -> ReasoningEffort.NONE
                ReasoningEffortType.MINIMAL -> ReasoningEffort.MINIMAL
                ReasoningEffortType.LOW -> ReasoningEffort.LOW
                ReasoningEffortType.MEDIUM -> ReasoningEffort.MEDIUM
                ReasoningEffortType.HIGH -> ReasoningEffort.HIGH
                ReasoningEffortType.XHIGH -> ReasoningEffort.XHIGH
            }
        }
        val mergedSummary: Reasoning.Summary? = (override.options?.summary ?: base.options?.summary)?.let { summary ->
            when (summary) {
                SummaryType.NONE -> throw UnsupportedSummaryTypeException(vendorName, summary)
                SummaryType.AUTO -> Reasoning.Summary.AUTO
                SummaryType.CONCISE -> Reasoning.Summary.CONCISE
                SummaryType.DETAILED -> Reasoning.Summary.DETAILED
            }
        }
        val mergedTools = mergeToolSpecs(base.tools, override.tools)
        val functionTools = mergedTools.filterIsInstance<ToolCallback<*, *>>()
        val fileSearchTools = mergedTools.filterIsInstance<LlmFileSearchTool>()
        val toolDefinitions = functionTools.map(ToolDefinitions::from)
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
            inputs = override.inputs ?: base.inputs?: emptyList(),
            model = override.modelKey ?: base.modelKey ?: DEFAULT_MODEL,
            maxOutputTokens = override.options?.maxTokens ?: base.options?.maxTokens,
            store = override.options?.store ?: base.options?.store,
            temperature = override.options?.temperature ?: base.options?.temperature,
            topP = override.options?.topP ?: base.options?.topP,
            include = mapInclude(override.options?.includeThoughts ?: base.options?.includeThoughts),
            reasoning = mapReasoning(mergedReasoningEffort, mergedSummary),
            text = mapTextConfig(
                textFormat = override.options?.textFormat ?: base.options?.textFormat,
                verbosity = override.options?.verbosity ?: base.options?.verbosity,
            ),
            toolChoice = override.toolChoice ?: base.toolChoice,
            toolHandlers = toolHandlers,
            toolsPayload = toolDefinitions.map(::toFunctionTool) + fileSearchTools.map(::toFileSearchTool),
            maxBufferedEvents = effectiveMaxBufferedEvents(override.options, base.options),
        )
    }

    private fun mapInclude(includeThoughts: Boolean?): List<ResponseIncludable> =
        if (includeThoughts == true) {
            listOf(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
        } else {
            emptyList()
        }

    private fun mapReasoning(
        effort: ReasoningEffort?,
        summary: Reasoning.Summary?,
    ): Reasoning? {
        if (effort == null && summary == null) {
            return null
        }

        return Reasoning.builder()
            .apply {
                effort?.let { effort(it) }
                summary?.let { summary(it) }
            }
            .build()
    }

    private fun mapTextConfig(
        textFormat: String?,
        verbosity: VerbosityType?,
    ): ResponseTextConfig? {
        val format = textFormat?.let(::mapTextFormat)
        val responseVerbosity = verbosity?.toOpenAiVerbosity()
        if (format == null && responseVerbosity == null) {
            return null
        }

        return ResponseTextConfig.builder()
            .apply {
                format?.let { format(it) }
                responseVerbosity?.let { verbosity(it) }
            }
            .build()
    }

    private fun mapTextFormat(textFormat: String): ResponseFormatTextConfig =
        when (textFormat) {
            "text" -> ResponseFormatTextConfig.ofText(ResponseFormatText.builder().build())
            "json_object" -> ResponseFormatTextConfig.ofJsonObject(ResponseFormatJsonObject.builder().build())
            else -> throw UnsupportedTextFormatException(vendorName, textFormat)
        }

    private fun VerbosityType.toOpenAiVerbosity(): ResponseTextConfig.Verbosity =
        when (this) {
            VerbosityType.LOW -> ResponseTextConfig.Verbosity.LOW
            VerbosityType.MEDIUM -> ResponseTextConfig.Verbosity.MEDIUM
            VerbosityType.HIGH -> ResponseTextConfig.Verbosity.HIGH
        }

    private fun convertInputs(items: List<LlmStreamInputItem>): List<ResponseInputItem> =
        items.map { item ->
            when (item) {
                is LlmMessageInputItem -> ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                        .role(item.role.toEasyRole())
                        .content(item.content)
                        .build()
                )
                else -> throw UnsupportedInputTypeException(item.type.name)
            }
        }

    private fun LlmMessageInputItem.Role.toEasyRole(): EasyInputMessage.Role =
        when (this) {
            LlmMessageInputItem.Role.USER -> EasyInputMessage.Role.USER
            LlmMessageInputItem.Role.ASSISTANT -> EasyInputMessage.Role.ASSISTANT
            LlmMessageInputItem.Role.SYSTEM -> EasyInputMessage.Role.SYSTEM
            LlmMessageInputItem.Role.DEVELOPER -> EasyInputMessage.Role.DEVELOPER
        }

    private fun toFunctionTool(definition: ToolDefinition): Tool {
        val normalizedSchema = OpenAiToolSchemaNormalizer.normalize(definition.inputSchema)
        val parameters = FunctionTool.Parameters.builder()
        normalizedSchema.forEach { (key, value) ->
            parameters.putAdditionalProperty(key, JsonValue.from(value))
        }

        return Tool.ofFunction(
            FunctionTool.builder()
                .name(definition.name)
                .description(definition.description)
                .parameters(parameters.build())
                .strict(true)
                .build()
        )
    }

    private fun toFileSearchTool(tool: LlmFileSearchTool): Tool {
        val builder = FileSearchTool.builder()
            .vectorStoreIds(tool.vectorStoreIds)
        tool.maxNumResults?.let(builder::maxNumResults)
        return Tool.ofFileSearch(builder.build())
    }

    private data class EffectiveRequest(
        val conversationId: String?,
        val previousResponseId: String?,
        val instructions: String?,
        val inputs: List<LlmStreamInputItem>,
        val model: String,
        val maxOutputTokens: Long?,
        val store: Boolean?,
        val temperature: Double?,
        val topP: Double?,
        val include: List<ResponseIncludable>,
        val reasoning: Reasoning?,
        val text: ResponseTextConfig?,
        val toolChoice: ResponseCreateParams.ToolChoice?,
        val toolHandlers: Map<String, ToolHandler>,
        val toolsPayload: List<Tool>,
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
        val responseInput: ResponseInputItem?,
        val directResult: DirectToolResult?,
        val output: String,
        val delivery: LlmStreamEvent.FunctionCallObserved.Delivery,
    )

    private data class DirectToolResult(
        val callId: String,
        val output: String,
    )

    private class ConversationContext(
        initialConversationId: String?,
        initialPreviousResponseId: String?,
    ) {
        var conversationId: String? = initialConversationId
            private set
        var lastResponseId: String? = initialPreviousResponseId
            private set

        fun updateFrom(response: Response) {
            lastResponseId = response.id()
            if (conversationId == null) {
                response.conversation().ifPresent { conversationId = it.id() }
            }
        }
    }

    private class StreamingState {
        private val current = AtomicReference<AsyncStreamResponse<ResponseStreamEvent>?>(null)

        fun replace(response: AsyncStreamResponse<ResponseStreamEvent>) {
            current.getAndSet(response)?.close()
        }

        fun clear(response: AsyncStreamResponse<ResponseStreamEvent>) {
            current.compareAndSet(response, null)
        }

        fun close() {
            current.getAndSet(null)?.close()
        }
    }

    private companion object {
        private const val DEFAULT_MODEL = "gpt-4.1-mini"
    }
}
