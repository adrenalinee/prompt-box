package mystix.prompt.llm.google

import com.openai.models.responses.ResponseCreateParams
import mu.KotlinLogging
import mystix.prompt.llm.*
import mystix.prompt.llm.exception.InputEmptyException
import mystix.prompt.llm.exception.ToolArgumentsParseException
import mystix.prompt.llm.exception.UnsupportedInputTypeException
import mystix.prompt.llm.exception.UnsupportedReasoningEffortTypeException
import mystix.prompt.llm.exception.UnsupportedSummaryTypeException
import mystix.prompt.llm.exception.UnsupportedTextFormatException
import mystix.prompt.llm.exception.UnsupportedToolException
import org.reactivestreams.Publisher
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GoogleLlmStreamClient3(
    private val rawClient: GoogleInteractionsStreamClient,
    private val defaultRequestSpec: LlmStreamRequest,
    private val jsonMapper: JsonMapper,
) : LlmStreamClient {
    private val logger = KotlinLogging.logger {}

    override val vendorName: String = "google"

    override fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent> {
        val effective = mergeSpecs(defaultRequestSpec, requestSpec)

        return Flux.create<LlmStreamEvent>({ sink ->
            val cancelled = AtomicBoolean(false)
            val currentSubscription = AtomicReference<Disposable?>(null)
            val initialSteps = buildInitialSteps(effective.inputs)
            val context = ConversationContext(effective.previousResponseId)
            val session = StreamSession(sink)

            if (initialSteps.isEmpty()) {
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
                input = GoogleInteractionInput.Steps(initialSteps),
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
        input: GoogleInteractionInput,
        context: ConversationContext,
        session: StreamSession,
        cancelled: AtomicBoolean,
        currentSubscription: AtomicReference<Disposable?>,
        sink: FluxSink<LlmStreamEvent>,
    ) {
        if (cancelled.get() || sink.isCancelled) {
            return
        }

        val pendingFunctionResults = mutableListOf<GoogleInteractionStep.FunctionResult>()
        val hadReturnDirect = AtomicBoolean(false)
        val roundCompleted = AtomicReference<LlmStreamEvent.ResponseCompleted?>()
        val streamItems = StreamItems()
        val request = GoogleInteractionsStreamRequest(
            model = spec.model,
            input = input,
            systemInstruction = spec.instructions,
            store = spec.store,
            generationConfig = spec.generationConfig,
            tools = spec.toolsPayload,
            responseFormat = spec.responseFormat,
            previousInteractionId = context.previousInteractionId(),
        )

        val source = rawClient.stream(request)
            .limitBufferedEvents(spec.maxBufferedEvents)
            .flatMapIterable { rawEvent ->
                mapRawEventToEmissions(
                    rawEvent = rawEvent,
                    context = context,
                    session = session,
                    items = streamItems,
                    roundCompleted = roundCompleted,
                    specModel = spec.model,
                )
            }
            .concatWith(
                Mono.fromCallable {
                    val tail = mutableListOf<StepEmission>()
                    closeAllOpenItems(session, streamItems, tail)
                    closeAllFunctionCallItems(session, streamItems, tail)
                    tail.add(StepEmission.EndOfRound(roundCompleted.get()))
                    tail.toList()
                }.flatMapIterable { it }
            )

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
                            pendingFunctionResults = pendingFunctionResults,
                            hadReturnDirect = hadReturnDirect,
                        ).cast(Any::class.java)
                    }
                    is StepEmission.EndOfRound -> {
                        if (hadReturnDirect.get()) {
                            session.finalizeSilently()
                        } else if (pendingFunctionResults.isEmpty()) {
                            session.finalize(unit.completed)
                        } else {
                            session.absorbRoundCompleted(unit.completed)
                            executeStep(
                                spec = spec,
                                input = GoogleInteractionInput.Steps(pendingFunctionResults.toList()),
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
                        logger.warn(error) { "google interactions stream error" }
                        session.error(error)
                    }
                },
                { /* completion is handled by EndOfRound */ },
            )

        currentSubscription.getAndSet(subscription)?.dispose()
    }

    private fun mapRawEventToEmissions(
        rawEvent: GoogleInteractionRawStreamEvent,
        context: ConversationContext,
        session: StreamSession,
        items: StreamItems,
        roundCompleted: AtomicReference<LlmStreamEvent.ResponseCompleted?>,
        specModel: String,
    ): List<StepEmission> {
        val node = jsonMapper.readTree(rawEvent.payload)
        return when (rawEvent.eventType) {
            "interaction.created" -> {
                val interaction = node.path("interaction")
                val responseId = interaction.textOrNull("id") ?: return emptyList()
                context.updateFrom(interaction)
                listOf(
                    StepEmission.PassThrough(
                        LlmStreamEvent.ResponseCreated(
                            id = responseId,
                            sequence = session.nextSequence.getAndIncrement(),
                            model = interaction.textOrNull("model") ?: specModel,
                        )
                    )
                )
            }

            "step.start" -> {
                mapStepStart(
                    step = node.path("step"),
                    nativeIndex = node.path("index").asInt(),
                    session = session,
                    items = items,
                )
            }

            "step.delta" -> {
                mapStepDelta(
                    delta = node.path("delta"),
                    nativeIndex = node.path("index").asInt(),
                    session = session,
                    items = items,
                )
            }

            "step.stop" -> {
                val emissions = mutableListOf<StepEmission>()
                closeStep(
                    nativeIndex = node.path("index").asInt(),
                    session = session,
                    items = items,
                    emissions = emissions,
                )
                emissions
            }

            "interaction.completed" -> {
                val interaction = node.path("interaction")
                context.updateFrom(interaction)
                throwIfTerminalFailure(interaction, rawEvent.payload)
                roundCompleted.set(buildResponseCompleted(interaction, session))
                emptyList()
            }

            "interaction.status_update" -> {
                val status = node.textOrNull("status")
                if (status in FAILURE_STATUSES) {
                    throw GoogleApiException(
                        statusCode = 500,
                        body = rawEvent.payload,
                        message = "Google interaction status is $status",
                    )
                }
                emptyList()
            }

            "error" -> {
                val error = node.path("error")
                throw GoogleApiException(
                    statusCode = error.path("status_code").asInt(500),
                    body = rawEvent.payload,
                    message = error.textOrNull("message")
                        ?: error.textOrNull("code")
                        ?: "Google interactions stream error",
                )
            }

            else -> emptyList()
        }
    }

    private fun mapStepStart(
        step: JsonNode,
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
    ): List<StepEmission> {
        val emissions = mutableListOf<StepEmission>()
        when (step.textOrNull("type")) {
            "model_output" -> {
                appendTextContentList(step.path("content"), nativeIndex, session, items, emissions)
            }
            "thought" -> {
                appendThoughtSummaryList(step.path("summary"), nativeIndex, session, items, emissions)
            }
            "function_call" -> {
                closeAllOpenItems(session, items, emissions)
                openFunctionCallItem(nativeIndex, items).merge(step)
            }
        }
        return emissions
    }

    private fun mapStepDelta(
        delta: JsonNode,
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
    ): List<StepEmission> {
        val emissions = mutableListOf<StepEmission>()
        when (delta.textOrNull("type")) {
            "text" -> {
                val text = delta.textOrNull("text").orEmpty()
                if (text.isNotEmpty()) {
                    val item = openTextItem(nativeIndex, session, items, emissions)
                    appendText(item, text, session, emissions)
                }
            }
            "thought_summary" -> {
                val content = delta.path("content")
                if (!content.isMissingNode && !content.isNull) {
                    appendThoughtSummaryContent(content, nativeIndex, session, items, emissions)
                }
            }
            "arguments_delta" -> {
                openFunctionCallItem(nativeIndex, items)
                    .appendArgumentsDelta(delta.textOrNull("arguments").orEmpty())
            }
            "function_call" -> {
                closeAllOpenItems(session, items, emissions)
                openFunctionCallItem(nativeIndex, items).merge(delta)
            }
        }
        return emissions
    }

    private fun appendTextContentList(
        content: JsonNode,
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        if (!content.isArray) return
        content.forEach { item ->
            if (item.textOrNull("type") == "text") {
                val text = item.textOrNull("text").orEmpty()
                if (text.isNotEmpty()) {
                    val openItem = openTextItem(nativeIndex, session, items, emissions)
                    appendText(openItem, text, session, emissions)
                }
            }
        }
    }

    private fun appendThoughtSummaryList(
        summary: JsonNode,
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        if (!summary.isArray) return
        summary.forEach { content ->
            appendThoughtSummaryContent(content, nativeIndex, session, items, emissions)
        }
    }

    private fun appendThoughtSummaryContent(
        content: JsonNode,
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        if (content.textOrNull("type") != "text") return
        val text = content.textOrNull("text").orEmpty()
        if (text.isEmpty()) return
        val item = openReasoningItem(nativeIndex, session, items, emissions)
        appendReasoning(item, text, session, emissions)
    }

    private fun openTextItem(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ): OpenTextItem {
        return items.textItems.getOrPut(nativeIndex) {
            val item = OpenTextItem(
                itemId = "msg-${UUID.randomUUID()}",
                itemIndex = session.nextItemIndex.getAndIncrement(),
            )
            emissions.add(
                StepEmission.PassThrough(
                    LlmStreamEvent.ResponseMessageAdded(
                        itemId = item.itemId,
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = item.itemIndex,
                    )
                )
            )
            emissions.add(
                StepEmission.PassThrough(
                    LlmStreamEvent.MessageContentPartAdded(
                        itemId = item.itemId,
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = item.itemIndex,
                        partIndex = 0L,
                    )
                )
            )
            item
        }
    }

    private fun appendText(
        item: OpenTextItem,
        text: String,
        session: StreamSession,
        emissions: MutableList<StepEmission>,
    ) {
        item.buffer.append(text)
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.MessageDelta(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                    partIndex = 0L,
                    delta = text,
                )
            )
        )
    }

    private fun openReasoningItem(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ): OpenReasoningItem {
        return items.reasoningItems.getOrPut(nativeIndex) {
            val item = OpenReasoningItem(
                itemId = "reason-${UUID.randomUUID()}",
                itemIndex = session.nextItemIndex.getAndIncrement(),
            )
            emissions.add(
                StepEmission.PassThrough(
                    LlmStreamEvent.ResponseReasoningAdded(
                        itemId = item.itemId,
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = item.itemIndex,
                    )
                )
            )
            emissions.add(
                StepEmission.PassThrough(
                    LlmStreamEvent.ReasoningSummaryPartAdded(
                        itemId = item.itemId,
                        sequence = session.nextSequence.getAndIncrement(),
                        itemIndex = item.itemIndex,
                        partIndex = 0L,
                    )
                )
            )
            item
        }
    }

    private fun appendReasoning(
        item: OpenReasoningItem,
        text: String,
        session: StreamSession,
        emissions: MutableList<StepEmission>,
    ) {
        item.buffer.append(text)
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.ReasoningDelta(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                    partIndex = 0L,
                    delta = text,
                )
            )
        )
    }

    private fun openFunctionCallItem(
        nativeIndex: Int,
        items: StreamItems,
    ): OpenFunctionCallItem =
        items.functionCallItems.getOrPut(nativeIndex) { OpenFunctionCallItem() }

    private fun closeStep(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        closeTextItem(nativeIndex, session, items, emissions)
        closeReasoningItem(nativeIndex, session, items, emissions)
        closeFunctionCallItem(nativeIndex, session, items, emissions)
    }

    private fun closeAllOpenItems(
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        items.textItems.keys.toList().forEach { closeTextItem(it, session, items, emissions) }
        items.reasoningItems.keys.toList().forEach { closeReasoningItem(it, session, items, emissions) }
    }

    private fun closeAllFunctionCallItems(
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        items.functionCallItems.keys.toList().forEach { closeFunctionCallItem(it, session, items, emissions) }
    }

    private fun closeTextItem(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        val item = items.textItems.remove(nativeIndex) ?: return
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.MessageContentPartDone(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                    partIndex = 0L,
                    text = item.buffer.toString(),
                )
            )
        )
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.ResponseMessageDone(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                )
            )
        )
    }

    private fun closeReasoningItem(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        val item = items.reasoningItems.remove(nativeIndex) ?: return
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.ReasoningSummaryPartDone(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                    partIndex = 0L,
                    text = item.buffer.toString(),
                )
            )
        )
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.ResponseReasoningDone(
                    itemId = item.itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = item.itemIndex,
                )
            )
        )
    }

    private fun closeFunctionCallItem(
        nativeIndex: Int,
        session: StreamSession,
        items: StreamItems,
        emissions: MutableList<StepEmission>,
    ) {
        val item = items.functionCallItems.remove(nativeIndex) ?: return
        val pending = item.toPendingFunctionCall(session) ?: return
        if (items.functionCallDedup.add(pending.dedupKey)) {
            emissions.add(StepEmission.ToolInvocation(pending))
        }
    }

    private fun buildResponseCompleted(
        interaction: JsonNode,
        session: StreamSession,
    ): LlmStreamEvent.ResponseCompleted? {
        val usage = interaction.path("usage")
        if (usage.isMissingNode || usage.isNull) return null
        return LlmStreamEvent.ResponseCompleted(
            id = interaction.textOrNull("id") ?: "",
            sequence = session.nextSequence.getAndIncrement(),
            inputTokens = usage.path("total_input_tokens").asLongOrNull(),
            cachedTokens = usage.path("total_cached_tokens").asLongOrNull(),
            outputTokens = usage.path("total_output_tokens").asLongOrNull(),
            reasoningTokens = usage.path("total_thought_tokens").asLongOrNull(),
            totalTokens = usage.path("total_tokens").asLongOrNull(),
        )
    }

    private fun throwIfTerminalFailure(
        interaction: JsonNode,
        payload: String,
    ) {
        val status = interaction.textOrNull("status")
        if (status in FAILURE_STATUSES) {
            throw GoogleApiException(
                statusCode = 500,
                body = payload,
                message = "Google interaction status is $status",
            )
        }
    }

    private fun handleToolInline(
        call: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
        session: StreamSession,
        pendingFunctionResults: MutableList<GoogleInteractionStep.FunctionResult>,
        hadReturnDirect: AtomicBoolean,
    ): Mono<Unit> {
        return invokeTool(call, handlers)
            .doOnNext { result ->
                emitFunctionCallDone(session, result)
                if (result.directResult != null) {
                    hadReturnDirect.set(true)
                    emitReturnDirectMessage(session, result.directResult)
                } else if (result.functionResult != null) {
                    pendingFunctionResults.add(result.functionResult)
                }
            }
            .onErrorResume { error ->
                emitFunctionCallFailed(session, call, error)
                Mono.error(error)
            }
            .then(Mono.empty())
    }

    private fun invokeTool(
        call: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
    ): Mono<ToolInvocationResult> {
        val handler = handlers[call.toolName]
            ?: return Mono.error(IllegalStateException("No tool callback registered for ${call.toolName}"))

        val parsedInput = try {
            jsonMapper.readValue(call.argumentsJson, handler.inputJavaType)
        } catch (ex: Exception) {
            return Mono.error(ToolArgumentsParseException(call.toolName, ex))
        }

        val publisher: Publisher<Any> = try {
            @Suppress("UNCHECKED_CAST")
            handler.callback.callback(parsedInput) as Publisher<Any>
        } catch (ex: Exception) {
            return Mono.error(ex)
        }

        return Flux.from(publisher)
            .switchIfEmpty(Flux.error(IllegalStateException("Tool ${call.toolName} returned no result")))
            .single()
            .map { result ->
                val serializedOutput = serializeToolResult(result)
                if (handler.callback.returnDirect) {
                    ToolInvocationResult(
                        request = call,
                        functionResult = null,
                        directResult = DirectToolResult(
                            callId = call.callId ?: "return-direct",
                            output = serializedOutput,
                        ),
                        output = serializedOutput,
                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT,
                    )
                } else {
                    ToolInvocationResult(
                        request = call,
                        functionResult = GoogleInteractionStep.FunctionResult(
                            callId = call.callId
                                ?: throw IllegalStateException("Google function call id is required"),
                            name = call.toolName,
                            result = listOf(GoogleInteractionContent.Text(serializedOutput)),
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

    private fun buildInitialSteps(inputs: List<LlmStreamInputItem>): List<GoogleInteractionStep> {
        return inputs.map { input ->
            when (input) {
                is LlmMessageInputItem -> input.toGoogleStep()
                else -> throw UnsupportedInputTypeException(input.type.name)
            }
        }
    }

    private fun LlmMessageInputItem.toGoogleStep(): GoogleInteractionStep {
        val content = listOf(GoogleInteractionContent.Text(content))
        return when (role) {
            LlmMessageInputItem.Role.USER -> GoogleInteractionStep.UserInput(content)
            LlmMessageInputItem.Role.ASSISTANT -> GoogleInteractionStep.ModelOutput(content)
            LlmMessageInputItem.Role.SYSTEM,
            LlmMessageInputItem.Role.DEVELOPER,
            -> throw UnsupportedInputTypeException(role.name)
        }
    }

    private fun mergeSpecs(base: LlmStreamRequest, override: LlmStreamRequest): EffectiveRequest {
        val mergedTools = mergeToolSpecs(base.tools, override.tools)
        val functionTools = mergedTools.requireFunctionToolsOnly("google")
        val toolDefinitions = functionTools.map(ToolDefinitions::from)
        @Suppress("UNCHECKED_CAST")
        val toolHandlers = functionTools.associate { callback ->
            callback.name to ToolHandler(
                callback = callback as ToolCallback<Any, Any?>,
                inputJavaType = callback.inputType.java,
            )
        }
        val options = mergeOptions(base.options, override.options)

        return EffectiveRequest(
            previousResponseId = override.previousResponseId ?: base.previousResponseId,
            instructions = override.instructions ?: base.instructions,
            inputs = override.inputs ?: base.inputs ?: emptyList(),
            model = override.modelKey ?: base.modelKey ?: DEFAULT_MODEL,
            store = options.store,
            generationConfig = buildGenerationConfig(
                temperature = options.temperature,
                topP = options.topP,
                maxTokens = options.maxTokens,
                effort = options.effort,
                includeThoughts = options.includeThoughts,
                summary = options.summary,
                toolChoice = override.toolChoice ?: base.toolChoice,
            ),
            responseFormat = mapTextResponse(options.textFormat),
            toolHandlers = toolHandlers,
            toolsPayload = toolDefinitions.map(::toFunctionTool),
            maxBufferedEvents = effectiveMaxBufferedEvents(override.options, base.options),
        )
    }

    private fun mergeOptions(
        base: LlmStreamOptions?,
        override: LlmStreamOptions?,
    ): MergedOptions {
        return MergedOptions(
            store = override?.store ?: base?.store,
            temperature = override?.temperature ?: base?.temperature,
            topP = override?.topP ?: base?.topP,
            maxTokens = override?.maxTokens ?: base?.maxTokens,
            includeThoughts = override?.includeThoughts ?: base?.includeThoughts,
            effort = override?.effort ?: base?.effort,
            summary = override?.summary ?: base?.summary,
            textFormat = override?.textFormat ?: base?.textFormat,
        )
    }

    private fun buildGenerationConfig(
        temperature: Double?,
        topP: Double?,
        maxTokens: Long?,
        effort: ReasoningEffortType?,
        includeThoughts: Boolean?,
        summary: SummaryType?,
        toolChoice: ResponseCreateParams.ToolChoice?,
    ): GoogleInteractionGenerationConfig? {
        val config = GoogleInteractionGenerationConfig(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens,
            thinkingLevel = effort?.let(::mapThinkingLevel),
            thinkingSummaries = mapThinkingSummaries(includeThoughts, summary),
            toolChoice = mapToolChoice(toolChoice),
        )

        return if (
            config.temperature == null &&
            config.topP == null &&
            config.maxOutputTokens == null &&
            config.thinkingLevel == null &&
            config.thinkingSummaries == null &&
            config.toolChoice == null
        ) {
            null
        } else {
            config
        }
    }

    private fun mapThinkingLevel(effort: ReasoningEffortType): String {
        return when (effort) {
            ReasoningEffortType.MINIMAL -> "minimal"
            ReasoningEffortType.LOW -> "low"
            ReasoningEffortType.MEDIUM -> "medium"
            ReasoningEffortType.HIGH -> "high"
            ReasoningEffortType.NONE,
            ReasoningEffortType.XHIGH,
            -> throw UnsupportedReasoningEffortTypeException(vendorName, effort)
        }
    }

    private fun mapThinkingSummaries(
        includeThoughts: Boolean?,
        summary: SummaryType?,
    ): String? {
        if (summary != null) {
            return when (summary) {
                SummaryType.AUTO -> "auto"
                SummaryType.NONE -> "none"
                SummaryType.CONCISE,
                SummaryType.DETAILED,
                -> throw UnsupportedSummaryTypeException(vendorName, summary)
            }
        }
        return includeThoughts?.let { if (it) "auto" else "none" }
    }

    private fun mapTextResponse(textFormat: String?): GoogleInteractionResponseFormat? {
        val format = textFormat?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (format) {
            "text" -> GoogleInteractionResponseFormat.Text(mimeType = "text/plain")
            "json_object" -> GoogleInteractionResponseFormat.Text(mimeType = "application/json")
            else -> throw UnsupportedTextFormatException(vendorName, textFormat)
        }
    }

    private fun mapToolChoice(choice: ResponseCreateParams.ToolChoice?): GoogleInteractionToolChoice? {
        if (choice == null) {
            return null
        }
        return choice.accept(
            object : ResponseCreateParams.ToolChoice.Visitor<GoogleInteractionToolChoice?> {
                override fun visitOptions(options: com.openai.models.responses.ToolChoiceOptions): GoogleInteractionToolChoice {
                    return when (options.value()) {
                        com.openai.models.responses.ToolChoiceOptions.Value.NONE -> GoogleInteractionToolChoice.Type("none")
                        com.openai.models.responses.ToolChoiceOptions.Value.AUTO -> GoogleInteractionToolChoice.Type("auto")
                        com.openai.models.responses.ToolChoiceOptions.Value.REQUIRED -> GoogleInteractionToolChoice.Type("any")
                        com.openai.models.responses.ToolChoiceOptions.Value._UNKNOWN -> GoogleInteractionToolChoice.Type("auto")
                    }
                }

                override fun visitAllowed(allowed: com.openai.models.responses.ToolChoiceAllowed): GoogleInteractionToolChoice {
                    val mode = when (allowed.mode().value()) {
                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value.AUTO -> "auto"
                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value.REQUIRED -> "any"
                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value._UNKNOWN -> "auto"
                    }
                    return GoogleInteractionToolChoice.AllowedTools(mode = mode)
                }

                override fun visitTypes(types: com.openai.models.responses.ToolChoiceTypes): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.Type("any")
                }

                override fun visitFunction(function: com.openai.models.responses.ToolChoiceFunction): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.AllowedTools(
                        mode = "any",
                        tools = listOf(function.name()),
                    )
                }

                override fun visitMcp(mcp: com.openai.models.responses.ToolChoiceMcp): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.Type("any")
                }

                override fun visitCustom(custom: com.openai.models.responses.ToolChoiceCustom): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.Type("any")
                }

                override fun visitApplyPatch(applyPatch: com.openai.models.responses.ToolChoiceApplyPatch): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.Type("any")
                }

                override fun visitShell(shell: com.openai.models.responses.ToolChoiceShell): GoogleInteractionToolChoice {
                    return GoogleInteractionToolChoice.Type("any")
                }
            }
        )
    }

    private fun List<LlmToolSpec>.requireFunctionToolsOnly(provider: String): List<ToolCallback<*, *>> {
        val unsupported = filterNot { it is ToolCallback<*, *> }
        if (unsupported.isNotEmpty()) {
            throw UnsupportedToolException(provider, unsupported.map { it.toolType })
        }
        return filterIsInstance<ToolCallback<*, *>>()
    }

    private fun toFunctionTool(definition: ToolDefinition): GoogleInteractionTool.Function {
        return GoogleInteractionTool.Function(
            name = definition.name,
            description = definition.description?.takeIf { it.isNotBlank() },
            parameters = definition.inputSchema,
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
            value.asString()
        }
    }

    private data class EffectiveRequest(
        val previousResponseId: String?,
        val instructions: String?,
        val inputs: List<LlmStreamInputItem>,
        val model: String,
        val store: Boolean?,
        val generationConfig: GoogleInteractionGenerationConfig?,
        val responseFormat: GoogleInteractionResponseFormat?,
        val toolHandlers: Map<String, ToolHandler>,
        val toolsPayload: List<GoogleInteractionTool>,
        val maxBufferedEvents: Int,
    )

    private data class MergedOptions(
        val store: Boolean?,
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Long?,
        val includeThoughts: Boolean?,
        val effort: ReasoningEffortType?,
        val summary: SummaryType?,
        val textFormat: String?,
    )

    private data class ToolHandler(
        val callback: ToolCallback<Any, Any?>,
        val inputJavaType: Class<*>,
    )

    private data class PendingFunctionCall(
        override val callId: String?,
        override val toolName: String,
        override val argumentsJson: String,
        override val requestSequence: Long? = null,
    ) : PendingFunctionCallContract {
        val dedupKey: String = "${callId.orEmpty()}:$toolName:$argumentsJson"
    }

    private data class ToolInvocationResult(
        val request: PendingFunctionCall,
        val functionResult: GoogleInteractionStep.FunctionResult?,
        val directResult: DirectToolResult?,
        val output: String,
        val delivery: LlmStreamEvent.FunctionCallObserved.Delivery,
    )

    private data class DirectToolResult(
        val callId: String,
        val output: String,
    )

    private data class OpenTextItem(
        val itemId: String,
        val itemIndex: Long,
        val buffer: StringBuilder = StringBuilder(),
    )

    private data class OpenReasoningItem(
        val itemId: String,
        val itemIndex: Long,
        val buffer: StringBuilder = StringBuilder(),
    )

    private inner class OpenFunctionCallItem {
        private var callId: String? = null
        private var toolName: String? = null
        private var arguments: Map<String, Any?>? = null
        private val argumentsDelta = StringBuilder()

        fun merge(stepOrDelta: JsonNode) {
            stepOrDelta.textOrNull("id")?.let { callId = it }
            stepOrDelta.textOrNull("name")?.let { toolName = it }
            val argumentsNode = stepOrDelta.path("arguments")
            if (!argumentsNode.isMissingNode && !argumentsNode.isNull) {
                arguments = jsonMapper.convertValue(
                    argumentsNode,
                    object : TypeReference<Map<String, Any?>>() {},
                )
            }
        }

        fun appendArgumentsDelta(delta: String) {
            argumentsDelta.append(delta)
        }

        fun toPendingFunctionCall(session: StreamSession): PendingFunctionCall? {
            val resolvedToolName = toolName ?: return null
            val argumentsJson = when {
                argumentsDelta.isNotEmpty() -> argumentsDelta.toString()
                arguments != null -> jsonMapper.writeValueAsString(arguments)
                else -> "{}"
            }
            return PendingFunctionCall(
                callId = callId,
                toolName = resolvedToolName,
                argumentsJson = argumentsJson,
                requestSequence = session.nextSequence.getAndIncrement(),
            )
        }
    }

    private class StreamItems {
        val textItems = linkedMapOf<Int, OpenTextItem>()
        val reasoningItems = linkedMapOf<Int, OpenReasoningItem>()
        val functionCallItems = linkedMapOf<Int, OpenFunctionCallItem>()
        val functionCallDedup = mutableSetOf<String>()
    }

    private inner class ConversationContext(initialPreviousInteractionId: String?) {
        private var lastInteractionId: String? = initialPreviousInteractionId

        fun previousInteractionId(): String? = lastInteractionId

        fun updateFrom(interaction: JsonNode) {
            interaction.textOrNull("id")?.let { lastInteractionId = it }
        }
    }

    private companion object {
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
        private val FAILURE_STATUSES = setOf("failed", "cancelled", "budget_exceeded")
    }
}
