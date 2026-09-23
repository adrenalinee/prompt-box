package malibu.llm.streamclient.google
//
//import com.google.genai.interactions.core.http.AsyncStreamResponse
//import com.google.genai.interactions.models.interactions.*
//import com.google.genai.interactions.services.async.InteractionServiceAsync
//import com.openai.models.responses.ResponseCreateParams
//import mu.KotlinLogging
//import mystix.prompt.llm.*
//import mystix.prompt.llm.exception.InputEmptyException
//import mystix.prompt.llm.exception.ToolArgumentsParseException
//import mystix.prompt.llm.exception.UnsupportedInputTypeException
//import mystix.prompt.llm.exception.UnsupportedReasoningEffortTypeException
//import mystix.prompt.llm.exception.UnsupportedToolException
//import org.reactivestreams.Publisher
//import org.reactivestreams.Subscriber
//import org.reactivestreams.Subscription
//import reactor.core.publisher.Flux
//import reactor.core.publisher.FluxSink
//import reactor.core.publisher.Mono
//import tools.jackson.databind.json.JsonMapper
//import tools.jackson.module.kotlin.KotlinModule
//import java.util.*
//import java.util.concurrent.atomic.AtomicBoolean
//import java.util.concurrent.atomic.AtomicReference
//import kotlin.jvm.optionals.getOrNull
//import com.google.genai.interactions.core.JsonValue as GoogleJsonValue
//import com.google.genai.interactions.models.interactions.Function as GoogleFunction
//import com.google.genai.interactions.models.interactions.Tool as GoogleInteractionTool
//
//class GoogleLlmStreamClient2(
//    private val interactions: InteractionServiceAsync,
//    private val defaultRequestSpec: LlmStreamRequest,
//    private val jsonMapper: JsonMapper,
//) : LlmStreamClient {
//
//    private val logger = KotlinLogging.logger {}
//
//    override val vendorName: String
//        get() = "google"
//
//    override fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent> {
//        val effective = mergeSpecs(defaultRequestSpec, requestSpec)
//
//        return Flux.create<LlmStreamEvent>({ sink ->
//            val initialTurns = buildInitialTurns(effective.inputs)
//            if (initialTurns.isEmpty()) {
//                sink.error(InputEmptyException())
//                return@create
//            }
//
//            val context = ConversationContext(effective.previousResponseId)
//            val streamingState = StreamingState()
//            val session = StreamSession(sink)
//
//            sink.onDispose { streamingState.close() }
//            executeStep(
//                spec = effective,
//                context = context,
//                input = InteractionInput.Turns(initialTurns),
//                state = streamingState,
//                session = session,
//                sink = sink,
//            )
//        }, FluxSink.OverflowStrategy.ERROR)
//            .limitBufferedEvents(effective.maxBufferedEvents)
//    }
//
//    private fun executeStep(
//        spec: EffectiveRequest,
//        context: ConversationContext,
//        input: InteractionInput,
//        state: StreamingState,
//        session: StreamSession,
//        sink: FluxSink<LlmStreamEvent>,
//    ) {
//        if (sink.isCancelled) {
//            return
//        }
//
//        val response = interactions.createStreaming(buildParams(spec, context, input))
//        state.replace(response)
//
//        val pendingFunctionResults = mutableListOf<Content>()
//        val hadReturnDirect = AtomicBoolean(false)
//        val roundCompleted = AtomicReference<LlmStreamEvent.ResponseCompleted?>()
//        val streamItems = StreamItems()
//
//        val sourceFlux = Flux.create<InteractionSseEvent>({ eventSink ->
//            response.subscribe(object : AsyncStreamResponse.Handler<InteractionSseEvent> {
//                override fun onNext(value: InteractionSseEvent) {
//                    if (eventSink.isCancelled) {
//                        state.clear(response)
//                        response.close()
//                        return
//                    }
//                    eventSink.next(value)
//                }
//
//                override fun onComplete(error: Optional<Throwable>) {
//                    state.clear(response)
//                    if (error.isPresent) {
//                        eventSink.error(error.get())
//                    } else {
//                        eventSink.complete()
//                    }
//                }
//            })
//            eventSink.onCancel { response.close() }
//        }, FluxSink.OverflowStrategy.ERROR)
//            .limitBufferedEvents(spec.maxBufferedEvents)
//
//        val emissionFlux = sourceFlux
//            .flatMapIterable { event ->
//                mapEventToEmissions(
//                    event = event,
//                    context = context,
//                    session = session,
//                    items = streamItems,
//                    roundCompleted = roundCompleted,
//                    specModel = spec.model,
//                )
//            }
//            .concatWith(
//                Mono.fromCallable {
//                    val tail = mutableListOf<StepEmission>()
//                    closeAllOpenItems(session, streamItems, tail)
//                    tail.add(StepEmission.EndOfRound(roundCompleted.get()))
//                    tail.toList()
//                }.flatMapIterable { it }
//            )
//
//        emissionFlux
//            .concatMap<Any> { unit ->
//                if (sink.isCancelled) {
//                    return@concatMap Mono.empty<Any>()
//                }
//                when (unit) {
//                    is StepEmission.PassThrough -> {
//                        if (hadReturnDirect.get()) return@concatMap Mono.empty<Any>()
//                        val event = unit.event
//                        if (event is LlmStreamEvent.ResponseCreated) {
//                            session.bufferResponseCreated(event)
//                        } else {
//                            session.emitExternal(event)
//                        }
//                        Mono.empty<Any>()
//                    }
//                    is StepEmission.ToolInvocation -> {
//                        if (hadReturnDirect.get()) return@concatMap Mono.empty<Any>()
//                        handleToolInline(
//                            call = unit.call as PendingFunctionCall,
//                            handlers = spec.toolHandlers,
//                            session = session,
//                            pendingFunctionResults = pendingFunctionResults,
//                            hadReturnDirect = hadReturnDirect,
//                        ).cast(Any::class.java)
//                    }
//                    is StepEmission.EndOfRound -> {
//                        if (hadReturnDirect.get()) {
//                            session.finalizeSilently()
//                        } else if (pendingFunctionResults.isEmpty()) {
//                            session.finalize(unit.completed)
//                        } else {
//                            session.absorbRoundCompleted(unit.completed)
//                            executeStep(
//                                spec = spec,
//                                context = context,
//                                input = InteractionInput.Contents(pendingFunctionResults.toList()),
//                                state = state,
//                                session = session,
//                                sink = sink,
//                            )
//                        }
//                        Mono.empty<Any>()
//                    }
//                }
//            }
//            .subscribe(
//                {},
//                { error ->
//                    if (!sink.isCancelled) {
//                        logger.warn(error) { "google interactions stream error" }
//                        session.error(error)
//                    }
//                },
//                { /* completion is handled by EndOfRound */ },
//            )
//    }
//
//    private fun mapEventToEmissions(
//        event: InteractionSseEvent,
//        context: ConversationContext,
//        session: StreamSession,
//        items: StreamItems,
//        roundCompleted: AtomicReference<LlmStreamEvent.ResponseCompleted?>,
//        specModel: String,
//    ): List<StepEmission> {
//        if (logger.isTraceEnabled) {
//            logger.trace { "map interactions event: $event" }
//        }
//
//        if (event.isStart()) {
//            val interaction = event.asStart().interaction()
//            context.updateFrom(interaction)
//            return listOf(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ResponseCreated(
//                        id = interaction.id(),
//                        sequence = session.nextSequence.getAndIncrement(),
//                        model = interaction.model().map { it.asString() }.orElse(specModel),
//                    )
//                )
//            )
//        }
//
//        if (event.isContentStart()) {
//            val start = event.asContentStart()
//            return mapContentStart(start.content(), start.index(), session, items)
//        }
//
//        if (event.isContentDelta()) {
//            val delta = event.asContentDelta()
//            return mapContentDelta(delta, session, items)
//        }
//
//        if (event.isContentStop()) {
//            val emissions = mutableListOf<StepEmission>()
//            closeOpenItem(event.asContentStop().index(), session, items, emissions)
//            return emissions
//        }
//
//        if (event.isComplete()) {
//            val interaction = event.asComplete().interaction()
//            context.updateFrom(interaction)
//            roundCompleted.set(buildResponseCompleted(interaction, session))
//            return emptyList()
//        }
//
//        if (event.isError()) {
//            val error = event.asError().error().getOrNull()
//            val code = error?.code()?.orElse("unknown") ?: "unknown"
//            val message = error?.message()?.orElse("unknown") ?: "unknown"
//            throw IllegalStateException("Google interactions error ($code): $message")
//        }
//
//        return emptyList()
//    }
//
//    private fun mapContentStart(
//        content: Content,
//        nativeIndex: Int,
//        session: StreamSession,
//        items: StreamItems,
//    ): List<StepEmission> {
//        val emissions = mutableListOf<StepEmission>()
//
//        when {
//            content.isText() -> {
//                val text = content.asText().textOrEmpty()
//                val item = openTextItem(nativeIndex, session, items, emissions)
//                if (text.isNotEmpty()) {
//                    appendText(item, text, session, emissions)
//                }
//            }
//            content.isFunctionCall() -> {
//                closeAllOpenItems(session, items, emissions)
//                openFunctionCallItem(nativeIndex, items).merge(content.asFunctionCall())
//            }
//            content.isThought() -> {
//                openReasoningItem(nativeIndex, session, items, emissions)
//            }
//        }
//
//        return emissions
//    }
//
//    private fun mapContentDelta(
//        event: ContentDelta,
//        session: StreamSession,
//        items: StreamItems,
//    ): List<StepEmission> {
//        val emissions = mutableListOf<StepEmission>()
//        val delta = event.delta()
//
//        when {
//            delta.isText() -> {
//                val text = delta.asText()._text().asString().getOrNull().orEmpty()
//                if (text.isNotEmpty()) {
//                    val item = openTextItem(event.index(), session, items, emissions)
//                    appendText(item, text, session, emissions)
//                }
//            }
//            delta.isThoughtSummary() -> {
//                val summaryContent = delta.asThoughtSummary().content().getOrNull()
//                val text = summaryContent?.text()?.map { it.textOrEmpty() }?.getOrNull()
//                if (!text.isNullOrEmpty()) {
//                    val item = openReasoningItem(event.index(), session, items, emissions)
//                    appendReasoning(item, text, session, emissions)
//                }
//            }
//            delta.isFunctionCall() -> {
//                closeAllOpenItems(session, items, emissions)
//                openFunctionCallItem(event.index(), items).merge(delta.asFunctionCall())
//            }
//        }
//
//        return emissions
//    }
//
//    private fun TextContent.textOrEmpty(): String =
//        _text().asString().getOrNull().orEmpty()
//
//    private fun openFunctionCallItem(
//        nativeIndex: Int,
//        items: StreamItems,
//    ): OpenFunctionCallItem =
//        items.functionCallItems.getOrPut(nativeIndex) { OpenFunctionCallItem() }
//
//    private fun openTextItem(
//        nativeIndex: Int,
//        session: StreamSession,
//        items: StreamItems,
//        emissions: MutableList<StepEmission>,
//    ): OpenTextItem {
//        return items.textItems.getOrPut(nativeIndex) {
//            val item = OpenTextItem(
//                itemId = "msg-${UUID.randomUUID()}",
//                itemIndex = session.nextItemIndex.getAndIncrement(),
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ResponseMessageAdded(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                    )
//                )
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.MessageContentPartAdded(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                        partIndex = 0L,
//                    )
//                )
//            )
//            item
//        }
//    }
//
//    private fun appendText(
//        item: OpenTextItem,
//        text: String,
//        session: StreamSession,
//        emissions: MutableList<StepEmission>,
//    ) {
//        item.buffer.append(text)
//        emissions.add(
//            StepEmission.PassThrough(
//                LlmStreamEvent.MessageDelta(
//                    itemId = item.itemId,
//                    sequence = session.nextSequence.getAndIncrement(),
//                    itemIndex = item.itemIndex,
//                    partIndex = 0L,
//                    delta = text,
//                )
//            )
//        )
//    }
//
//    private fun openReasoningItem(
//        nativeIndex: Int,
//        session: StreamSession,
//        items: StreamItems,
//        emissions: MutableList<StepEmission>,
//    ): OpenReasoningItem {
//        return items.reasoningItems.getOrPut(nativeIndex) {
//            val item = OpenReasoningItem(
//                itemId = "reason-${UUID.randomUUID()}",
//                itemIndex = session.nextItemIndex.getAndIncrement(),
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ResponseReasoningAdded(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                    )
//                )
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ReasoningSummaryPartAdded(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                        partIndex = 0L,
//                    )
//                )
//            )
//            item
//        }
//    }
//
//    private fun appendReasoning(
//        item: OpenReasoningItem,
//        text: String,
//        session: StreamSession,
//        emissions: MutableList<StepEmission>,
//    ) {
//        item.buffer.append(text)
//        emissions.add(
//            StepEmission.PassThrough(
//                LlmStreamEvent.ReasoningDelta(
//                    itemId = item.itemId,
//                    sequence = session.nextSequence.getAndIncrement(),
//                    itemIndex = item.itemIndex,
//                    partIndex = 0L,
//                    delta = text,
//                )
//            )
//        )
//    }
//
//    private fun closeOpenItem(
//        nativeIndex: Int,
//        session: StreamSession,
//        items: StreamItems,
//        emissions: MutableList<StepEmission>,
//    ) {
//        items.textItems.remove(nativeIndex)?.let { item ->
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.MessageContentPartDone(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                        partIndex = 0L,
//                        text = item.buffer.toString(),
//                    )
//                )
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ResponseMessageDone(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                    )
//                )
//            )
//        }
//
//        items.reasoningItems.remove(nativeIndex)?.let { item ->
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ReasoningSummaryPartDone(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                        partIndex = 0L,
//                        text = item.buffer.toString(),
//                    )
//                )
//            )
//            emissions.add(
//                StepEmission.PassThrough(
//                    LlmStreamEvent.ResponseReasoningDone(
//                        itemId = item.itemId,
//                        sequence = session.nextSequence.getAndIncrement(),
//                        itemIndex = item.itemIndex,
//                    )
//                )
//            )
//        }
//
//        items.functionCallItems.remove(nativeIndex)?.let { item ->
//            item.toPendingFunctionCall(session)?.let { call ->
//                if (items.functionCallDedup.add(call.dedupKey)) {
//                    emissions.add(StepEmission.ToolInvocation(call))
//                }
//            }
//        }
//    }
//
//    private fun closeAllOpenItems(
//        session: StreamSession,
//        items: StreamItems,
//        emissions: MutableList<StepEmission>,
//    ) {
//        val indexes = (items.textItems.keys + items.reasoningItems.keys).toSortedSet()
//        indexes.forEach { index -> closeOpenItem(index, session, items, emissions) }
//    }
//
//    private fun buildResponseCompleted(
//        interaction: Interaction,
//        session: StreamSession,
//    ): LlmStreamEvent.ResponseCompleted? {
//        val usage = interaction.usage().getOrNull() ?: return null
//        return LlmStreamEvent.ResponseCompleted(
//            id = interaction.id(),
//            sequence = session.nextSequence.getAndIncrement(),
//            inputTokens = usage.totalInputTokens().toLongOrNull(),
//            cachedTokens = usage.totalCachedTokens().toLongOrNull(),
//            outputTokens = usage.totalOutputTokens().toLongOrNull(),
//            reasoningTokens = usage.totalThoughtTokens().toLongOrNull(),
//            totalTokens = usage.totalTokens().toLongOrNull(),
//        )
//    }
//
//    private fun Optional<Int>.toLongOrNull(): Long? = map { it.toLong() }.getOrNull()
//
//    private fun handleToolInline(
//        call: PendingFunctionCall,
//        handlers: Map<String, ToolHandler>,
//        session: StreamSession,
//        pendingFunctionResults: MutableList<Content>,
//        hadReturnDirect: AtomicBoolean,
//    ): Mono<Unit> {
//        return invokeTool(call, handlers)
//            .doOnNext { result ->
//                emitFunctionCallDone(session, result)
//                if (result.directResult != null) {
//                    hadReturnDirect.set(true)
//                    emitReturnDirectMessage(session, result.directResult)
//                } else if (result.functionResultContent != null) {
//                    pendingFunctionResults.add(result.functionResultContent)
//                }
//            }
//            .onErrorResume { error ->
//                emitFunctionCallFailed(session, call, error)
//                Mono.error(error)
//            }
//            .then(Mono.empty())
//    }
//
//    private fun invokeTool(
//        call: PendingFunctionCall,
//        handlers: Map<String, ToolHandler>,
//    ): Mono<ToolInvocationResult> {
//        val handler = handlers[call.toolName]
//            ?: return Mono.error(IllegalStateException("No tool callback registered for ${call.toolName}"))
//
//        val parsedArguments = try {
//            jsonMapper.readValue(call.argumentsJson, handler.inputJavaType)
//        } catch (ex: Exception) {
//            return Mono.error(ToolArgumentsParseException(call.toolName, ex))
//        }
//
//        val publisher: Publisher<Any?> = try {
//            @Suppress("UNCHECKED_CAST")
//            handler.callback.callback(parsedArguments) as Publisher<Any?>
//        } catch (ex: Exception) {
//            return Mono.error(ex)
//        }
//
//        val safeFlux = Flux.create<Any> { sink ->
//            publisher.subscribe(object : Subscriber<Any?> {
//                private var subscription: Subscription? = null
//
//                override fun onSubscribe(sub: Subscription) {
//                    subscription = sub
//                    sink.onCancel { sub.cancel() }
//                    sub.request(Long.MAX_VALUE)
//                }
//
//                override fun onNext(value: Any?) {
//                    if (value == null) {
//                        subscription?.cancel()
//                        sink.error(IllegalStateException("Tool ${call.toolName} returned null result"))
//                    } else {
//                        sink.next(value)
//                    }
//                }
//
//                override fun onError(t: Throwable) {
//                    sink.error(t)
//                }
//
//                override fun onComplete() {
//                    sink.complete()
//                }
//            })
//        }
//
//        return safeFlux
//            .switchIfEmpty(Flux.error(IllegalStateException("Tool ${call.toolName} returned no result")))
//            .single()
//            .map { result ->
//                val serializedOutput = serializeToolResult(result)
//                if (handler.callback.returnDirect) {
//                    ToolInvocationResult(
//                        request = call,
//                        functionResultContent = null,
//                        directResult = DirectToolResult(
//                            callId = call.callId ?: "return-direct",
//                            output = serializedOutput,
//                        ),
//                        output = serializedOutput,
//                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT,
//                    )
//                } else {
//                    ToolInvocationResult(
//                        request = call,
//                        functionResultContent = Content.ofFunctionResult(
//                            FunctionResultContent.builder()
//                                .callId(call.callId ?: UUID.randomUUID().toString())
//                                .name(call.toolName)
//                                .result(GoogleJsonValue.from(normalizeToolOutput(result)))
//                                .apply { call.signature?.let(::signature) }
//                                .build()
//                        ),
//                        directResult = null,
//                        output = serializedOutput,
//                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL,
//                    )
//                }
//            }
//    }
//
//    private fun emitFunctionCallDone(
//        session: StreamSession,
//        result: ToolInvocationResult,
//    ) {
//        if (session.isCancelled) return
//        session.emitExternal(
//            LlmStreamEvent.FunctionCallObserved(
//                callId = result.request.callId,
//                toolName = result.request.toolName,
//                sequence = result.request.requestSequence,
//                argumentsJson = result.request.argumentsJson,
//                output = result.output,
//                delivery = result.delivery,
//            )
//        )
//    }
//
//    private fun emitFunctionCallFailed(
//        session: StreamSession,
//        request: PendingFunctionCall,
//        error: Throwable,
//    ) {
//        if (session.isCancelled) return
//        session.emitExternal(
//            LlmStreamEvent.FunctionCallObserved(
//                callId = request.callId,
//                toolName = request.toolName,
//                sequence = request.requestSequence,
//                argumentsJson = request.argumentsJson,
//                errorMessage = error.message ?: error::class.simpleName,
//            )
//        )
//    }
//
//    private fun emitReturnDirectMessage(
//        session: StreamSession,
//        result: DirectToolResult,
//    ) {
//        if (session.isCancelled) return
//        val itemId = "return-direct-${result.callId}-${UUID.randomUUID()}"
//        val itemIndex = session.nextItemIndex.getAndIncrement()
//        session.emitExternal(
//            LlmStreamEvent.ResponseMessageAdded(
//                itemId = itemId,
//                sequence = session.nextSequence.getAndIncrement(),
//                itemIndex = itemIndex,
//            )
//        )
//        session.emitExternal(
//            LlmStreamEvent.MessageContentPartAdded(
//                itemId = itemId,
//                sequence = session.nextSequence.getAndIncrement(),
//                itemIndex = itemIndex,
//                partIndex = 0L,
//            )
//        )
//        session.emitExternal(
//            LlmStreamEvent.MessageDelta(
//                itemId = itemId,
//                sequence = session.nextSequence.getAndIncrement(),
//                itemIndex = itemIndex,
//                partIndex = 0L,
//                delta = result.output,
//            )
//        )
//        session.emitExternal(
//            LlmStreamEvent.MessageContentPartDone(
//                itemId = itemId,
//                sequence = session.nextSequence.getAndIncrement(),
//                itemIndex = itemIndex,
//                partIndex = 0L,
//                text = result.output,
//            )
//        )
//        session.emitExternal(
//            LlmStreamEvent.ResponseMessageDone(
//                itemId = itemId,
//                sequence = session.nextSequence.getAndIncrement(),
//                itemIndex = itemIndex,
//            )
//        )
//    }
//
//    private fun buildParams(
//        spec: EffectiveRequest,
//        context: ConversationContext,
//        input: InteractionInput,
//    ): InteractionCreateParams {
//        val modelBuilder = CreateModelInteractionParams.builder()
//            .model(spec.model)
//
//        when (input) {
//            is InteractionInput.Turns -> modelBuilder.inputOfTurnList(input.turns)
//            is InteractionInput.Contents -> modelBuilder.inputOfContentList(input.contents)
//        }
//
//        spec.instructions?.let(modelBuilder::systemInstruction)
//        spec.store?.let(modelBuilder::store)
//        buildGenerationConfig(spec)?.let(modelBuilder::generationConfig)
//        if (spec.toolsPayload.isNotEmpty()) {
//            modelBuilder.tools(spec.toolsPayload)
//        }
//        context.previousInteractionId()?.let(modelBuilder::previousInteractionId)
//
//        return InteractionCreateParams.builder()
//            .body(modelBuilder.build())
//            .build()
//    }
//
//    private fun buildGenerationConfig(spec: EffectiveRequest): GenerationConfig? {
//        val builder = GenerationConfig.builder()
//        var configured = false
//
//        spec.maxOutputTokens?.let {
//            builder.maxOutputTokens(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
//            configured = true
//        }
//        spec.temperature?.let {
//            builder.temperature(it.toFloat())
//            configured = true
//        }
//        spec.topP?.let {
//            builder.topP(it.toFloat())
//            configured = true
//        }
//        spec.reasoningEffort?.let { effort ->
//            mapThinkingLevel(effort)?.let {
//                builder.thinkingLevel(it)
//                configured = true
//            }
//        }
//        when {
//            spec.includeThoughts == true || spec.summary != null -> {
//                builder.thinkingSummaries(GenerationConfig.ThinkingSummaries.AUTO)
//                configured = true
//            }
//            spec.includeThoughts == false -> {
//                builder.thinkingSummaries(GenerationConfig.ThinkingSummaries.NONE)
//                configured = true
//            }
//        }
//        mapToolChoice(spec.toolChoice)?.let {
//            builder.toolChoice(it)
//            configured = true
//        }
//
//        return if (configured) builder.build() else null
//    }
//
//    private fun mapThinkingLevel(effort: ReasoningEffortType): ThinkingLevel? =
//        when (effort) {
//            ReasoningEffortType.MINIMAL -> ThinkingLevel.MINIMAL
//            ReasoningEffortType.LOW -> ThinkingLevel.LOW
//            ReasoningEffortType.MEDIUM -> ThinkingLevel.MEDIUM
//            ReasoningEffortType.HIGH -> ThinkingLevel.HIGH
////            ReasoningEffortType.NONE ->
////            ReasoningEffortType.XHIGH ->
//            else -> throw UnsupportedReasoningEffortTypeException(vendorName, effort)
//        }
//
//    private fun mapToolChoice(choice: ResponseCreateParams.ToolChoice?): GenerationConfig.ToolChoice? {
//        if (choice == null) {
//            return null
//        }
//        return choice.accept(
//            object : ResponseCreateParams.ToolChoice.Visitor<GenerationConfig.ToolChoice?> {
//                override fun visitOptions(options: com.openai.models.responses.ToolChoiceOptions): GenerationConfig.ToolChoice? {
//                    val type = when (options.value()) {
//                        com.openai.models.responses.ToolChoiceOptions.Value.NONE -> ToolChoiceType.NONE
//                        com.openai.models.responses.ToolChoiceOptions.Value.AUTO -> ToolChoiceType.AUTO
//                        com.openai.models.responses.ToolChoiceOptions.Value.REQUIRED -> ToolChoiceType.ANY
//                        com.openai.models.responses.ToolChoiceOptions.Value._UNKNOWN -> ToolChoiceType.AUTO
//                    }
//                    return GenerationConfig.ToolChoice.ofType(type)
//                }
//
//                override fun visitAllowed(allowed: com.openai.models.responses.ToolChoiceAllowed): GenerationConfig.ToolChoice? {
//                    val mode = when (allowed.mode().value()) {
//                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value.AUTO -> ToolChoiceType.AUTO
//                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value.REQUIRED -> ToolChoiceType.ANY
//                        com.openai.models.responses.ToolChoiceAllowed.Mode.Value._UNKNOWN -> ToolChoiceType.AUTO
//                    }
//                    return GenerationConfig.ToolChoice.ofConfig(
//                        ToolChoiceConfig.builder()
//                            .allowedTools(AllowedTools.builder().mode(mode).build())
//                            .build()
//                    )
//                }
//
//                override fun visitFunction(function: com.openai.models.responses.ToolChoiceFunction): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofConfig(
//                        ToolChoiceConfig.builder()
//                            .allowedTools(
//                                AllowedTools.builder()
//                                    .mode(ToolChoiceType.ANY)
//                                    .addTool(function.name())
//                                    .build()
//                            )
//                            .build()
//                    )
//                }
//
//                override fun visitTypes(types: com.openai.models.responses.ToolChoiceTypes): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofType(ToolChoiceType.ANY)
//                }
//
//                override fun visitMcp(mcp: com.openai.models.responses.ToolChoiceMcp): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofType(ToolChoiceType.ANY)
//                }
//
//                override fun visitCustom(custom: com.openai.models.responses.ToolChoiceCustom): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofType(ToolChoiceType.ANY)
//                }
//
//                override fun visitApplyPatch(applyPatch: com.openai.models.responses.ToolChoiceApplyPatch): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofType(ToolChoiceType.ANY)
//                }
//
//                override fun visitShell(shell: com.openai.models.responses.ToolChoiceShell): GenerationConfig.ToolChoice? {
//                    return GenerationConfig.ToolChoice.ofType(ToolChoiceType.ANY)
//                }
//            }
//        )
//    }
//
//    private fun buildInitialTurns(items: List<LlmStreamInputItem>): List<Turn> =
//        items.map { item ->
//            when (item) {
//                is LlmMessageInputItem -> Turn.builder()
//                    .role(item.role.toGoogleRole())
//                    .content(item.content)
//                    .build()
//                else -> throw UnsupportedInputTypeException(item.type.name)
//            }
//        }
//
//    private fun LlmMessageInputItem.Role.toGoogleRole(): String =
//        when (this) {
//            LlmMessageInputItem.Role.USER -> "user"
//            LlmMessageInputItem.Role.ASSISTANT -> "model"
//            LlmMessageInputItem.Role.SYSTEM,
//            LlmMessageInputItem.Role.DEVELOPER,
//            -> throw UnsupportedInputTypeException(this.name)
//        }
//
//    private fun FunctionCallContent.argumentsOrEmpty(): Map<String, GoogleJsonValue> =
//        _arguments().asKnown().map { it._additionalProperties() }.orElse(emptyMap())
//
//    private fun ContentDelta.Delta.FunctionCall.argumentsOrEmpty(): Map<String, GoogleJsonValue> =
//        _arguments().asKnown().map { it._additionalProperties() }.orElse(emptyMap())
//
//    private fun Map<String, GoogleJsonValue>.toPlainMap(): Map<String, Any?> =
//        mapValues { (_, value) -> value.toPlainValue() }
//
//    private fun GoogleJsonValue.toPlainValue(): Any? =
//        accept(
//            object : GoogleJsonValue.Visitor<Any?> {
//                override fun visitNull(): Any? = null
//
//                override fun visitMissing(): Any? = null
//
//                override fun visitBoolean(value: Boolean): Any = value
//
//                override fun visitNumber(value: Number): Any = value
//
//                override fun visitString(value: String): Any = value
//
//                override fun visitArray(values: List<GoogleJsonValue>): Any =
//                    values.map { it.toPlainValue() }
//
//                override fun visitObject(values: Map<String, GoogleJsonValue>): Any =
//                    values.toPlainMap()
//            }
//        )
//
//    private fun toFunctionTool(definition: ToolDefinition): GoogleInteractionTool {
//        val builder = GoogleFunction.builder()
//            .name(definition.name)
//            .parameters(GoogleJsonValue.from(definition.inputSchema))
//        definition.description?.let(builder::description)
//        return GoogleInteractionTool.ofFunction(builder.build())
//    }
//
//    private fun mergeSpecs(base: LlmStreamRequest, override: LlmStreamRequest): EffectiveRequest {
//        val mergedTools = mergeToolSpecs(base.tools, override.tools)
//        val functionTools = mergedTools.requireFunctionToolsOnly("google")
//        val toolDefinitions = functionTools.map(ToolDefinitions::from)
//        @Suppress("UNCHECKED_CAST")
//        val toolHandlers = functionTools.associate { callback ->
//            callback.name to ToolHandler(
//                callback = callback as ToolCallback<Any, Any?>,
//                inputJavaType = callback.inputType.java,
//            )
//        }
//
//        return EffectiveRequest(
//            previousResponseId = override.previousResponseId ?: base.previousResponseId,
//            instructions = override.instructions ?: base.instructions,
//            inputs = override.inputs ?: base.inputs ?: emptyList(),
//            model = override.modelKey ?: base.modelKey ?: DEFAULT_MODEL,
//            maxOutputTokens = override.options?.maxTokens ?: base.options?.maxTokens,
//            store = override.options?.store ?: base.options?.store,
//            temperature = override.options?.temperature ?: base.options?.temperature,
//            topP = override.options?.topP ?: base.options?.topP,
//            includeThoughts = override.options?.includeThoughts ?: base.options?.includeThoughts,
//            reasoningEffort = override.options?.effort ?: base.options?.effort,
//            summary = override.options?.summary ?: base.options?.summary,
//            toolChoice = override.toolChoice ?: base.toolChoice,
//            toolHandlers = toolHandlers,
//            toolsPayload = toolDefinitions.map(::toFunctionTool),
//            maxBufferedEvents = effectiveMaxBufferedEvents(override.options, base.options),
//        )
//    }
//
//    private fun List<LlmToolSpec>.requireFunctionToolsOnly(provider: String): List<ToolCallback<*, *>> {
//        val unsupported = filterNot { it is ToolCallback<*, *> }
//        if (unsupported.isNotEmpty()) {
//            throw UnsupportedToolException(provider, unsupported.map { it.toolType })
//        }
//        return filterIsInstance<ToolCallback<*, *>>()
//    }
//
//    private fun serializeToolResult(result: Any?): String {
//        return when (result) {
//            null -> "null"
//            is String -> result
//            is Number, is Boolean -> result.toString()
//            else -> jsonMapper.writeValueAsString(result)
//        }
//    }
//
//    private fun normalizeToolOutput(result: Any?): Any? {
//        return when (result) {
//            null -> null
//            is String, is Number, is Boolean, is Map<*, *>, is List<*> -> result
//            else -> jsonMapper.convertValue(result, Any::class.java)
//        }
//    }
//
//    private sealed interface InteractionInput {
//        data class Turns(val turns: List<Turn>) : InteractionInput
//        data class Contents(val contents: List<Content>) : InteractionInput
//    }
//
//    private data class EffectiveRequest(
//        val previousResponseId: String?,
//        val instructions: String?,
//        val inputs: List<LlmStreamInputItem>,
//        val model: String,
//        val maxOutputTokens: Long?,
//        val store: Boolean?,
//        val temperature: Double?,
//        val topP: Double?,
//        val includeThoughts: Boolean?,
//        val reasoningEffort: ReasoningEffortType?,
//        val summary: SummaryType?,
//        val toolChoice: ResponseCreateParams.ToolChoice?,
//        val toolHandlers: Map<String, ToolHandler>,
//        val toolsPayload: List<GoogleInteractionTool>,
//        val maxBufferedEvents: Int,
//    )
//
//    private data class ToolHandler(
//        val callback: ToolCallback<Any, Any?>,
//        val inputJavaType: Class<*>,
//    )
//
//    private data class PendingFunctionCall(
//        override val callId: String?,
//        override val toolName: String,
//        override val argumentsJson: String,
//        val signature: String?,
//        override val requestSequence: Long? = null,
//    ) : PendingFunctionCallContract {
//        val dedupKey: String = "${callId ?: ""}:$toolName:$argumentsJson"
//    }
//
//    private data class ToolInvocationResult(
//        val request: PendingFunctionCall,
//        val functionResultContent: Content?,
//        val directResult: DirectToolResult?,
//        val output: String,
//        val delivery: LlmStreamEvent.FunctionCallObserved.Delivery,
//    )
//
//    private data class DirectToolResult(
//        val callId: String,
//        val output: String,
//    )
//
//    private data class OpenTextItem(
//        val itemId: String,
//        val itemIndex: Long,
//        val buffer: StringBuilder = StringBuilder(),
//    )
//
//    private data class OpenReasoningItem(
//        val itemId: String,
//        val itemIndex: Long,
//        val buffer: StringBuilder = StringBuilder(),
//    )
//
//    private inner class OpenFunctionCallItem {
//        private var callId: String? = null
//        private var toolName: String? = null
//        private var signature: String? = null
//        private val arguments = linkedMapOf<String, GoogleJsonValue>()
//
//        fun merge(content: FunctionCallContent) {
//            content._id().asString().getOrNull()?.let { callId = it }
//            content._name().asString().getOrNull()?.let { toolName = it }
//            content._signature().asString().getOrNull()?.let { signature = it }
//            arguments.putAll(content.argumentsOrEmpty())
//        }
//
//        fun merge(content: ContentDelta.Delta.FunctionCall) {
//            content._id().asString().getOrNull()?.let { callId = it }
//            content._name().asString().getOrNull()?.let { toolName = it }
//            content._signature().asString().getOrNull()?.let { signature = it }
//            arguments.putAll(content.argumentsOrEmpty())
//        }
//
//        fun toPendingFunctionCall(session: StreamSession): PendingFunctionCall? {
//            val resolvedToolName = toolName ?: return null
//            return PendingFunctionCall(
//                callId = callId,
//                toolName = resolvedToolName,
//                argumentsJson = jsonMapper.writeValueAsString(arguments.toPlainMap()),
//                signature = signature,
//                requestSequence = session.nextSequence.getAndIncrement(),
//            )
//        }
//    }
//
//    private class StreamItems {
//        val textItems = linkedMapOf<Int, OpenTextItem>()
//        val reasoningItems = linkedMapOf<Int, OpenReasoningItem>()
//        val functionCallItems = linkedMapOf<Int, OpenFunctionCallItem>()
//        val functionCallDedup = mutableSetOf<String>()
//    }
//
//    private class ConversationContext(initialPreviousInteractionId: String?) {
//        private var lastInteractionId: String? = initialPreviousInteractionId
//
//        fun previousInteractionId(): String? = lastInteractionId
//
//        fun updateFrom(interaction: Interaction) {
//            lastInteractionId = interaction.id()
//        }
//    }
//
//    private class StreamingState {
//        private val current = AtomicReference<AsyncStreamResponse<InteractionSseEvent>?>()
//
//        fun replace(response: AsyncStreamResponse<InteractionSseEvent>) {
//            current.getAndSet(response)?.close()
//        }
//
//        fun clear(response: AsyncStreamResponse<InteractionSseEvent>) {
//            current.compareAndSet(response, null)
//        }
//
//        fun close() {
//            current.getAndSet(null)?.close()
//        }
//    }
//
//    private companion object {
//        private const val DEFAULT_MODEL = "gemini-2.5-flash"
//
//        private fun defaultJsonMapper(): JsonMapper =
//            tools.jackson.module.kotlin.jsonMapper {
//                addModule(KotlinModule.Builder().build())
//            }
//    }
//}
