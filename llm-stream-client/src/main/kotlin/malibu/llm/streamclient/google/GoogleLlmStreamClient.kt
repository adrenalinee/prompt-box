package malibu.llm.streamclient.google

import com.openai.models.responses.*
import malibu.llm.streamclient.*
import malibu.llm.streamclient.exception.InputEmptyException
import malibu.llm.streamclient.exception.ToolArgumentsParseException
import malibu.llm.streamclient.exception.UnsupportedInputTypeException
import malibu.llm.streamclient.exception.UnsupportedToolException
import mu.KotlinLogging
import org.reactivestreams.Publisher
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GoogleLlmStreamClient(
    private val streamClient: GoogleGenerateContentStreamClient,
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
            val session = StreamSession(sink)
            val initialContents = buildInitialContents(effective.inputs)

            if (effective.instructions.isNullOrBlank() && initialContents.isEmpty()) {
                sink.error(InputEmptyException())
                return@create
            }
            if (initialContents.isEmpty()) {
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
                state = ConversationState(initialContents.toMutableList()),
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
        state: ConversationState,
        session: StreamSession,
        cancelled: AtomicBoolean,
        currentSubscription: AtomicReference<Disposable?>,
        sink: FluxSink<LlmStreamEvent>,
    ) {
        if (cancelled.get() || sink.isCancelled) {
            return
        }

        val request = GoogleGenerateContentStreamRequest(
            model = spec.model,
            contents = state.contents,
            systemInstruction = spec.instructions,
            temperature = spec.temperature,
            topP = spec.topP,
            topK = spec.topK,
            includeThoughts = spec.includeThoughts,
            maxOutputTokens = spec.maxTokens,
            tools = spec.toolDefinitions.map { definition ->
                GoogleFunctionDeclaration(
                    name = definition.name,
                    description = definition.description,
                    parametersJsonSchema = definition.inputSchema,
                )
            },
            toolChoiceMode = mapFunctionCallingMode(spec.toolChoice),
        )

        val responseIdFallback = "resp-${UUID.randomUUID()}"
        val responseIdRef = AtomicReference(responseIdFallback)
        val responseCreatedBuffered = AtomicBoolean(false)
        val latestUsage = AtomicReference<GoogleUsage?>()
        val pendingFunctionCalls = mutableListOf<PendingFunctionCall>()
        val functionCallDedup = mutableSetOf<String>()
        val pendingFunctionResponses = mutableListOf<FunctionResponsePayload>()
        val hadReturnDirect = AtomicBoolean(false)

        // 메시지 아이템 상태 — text part 가 연속되는 동안 같은 메시지 아이템 안에 누적,
        // FunctionCall 을 만나면 닫고 새 아이템을 연다.
        val openMessageItemId = AtomicReference<String?>()
        val openMessageItemIndex = AtomicReference<Long?>()
        val openMessageBuffer = StringBuilder()

        val source = streamClient.stream(request)
            .limitBufferedEvents(spec.maxBufferedEvents)
            .flatMapIterable { chunk ->
                buildChunkEmissions(
                    chunk = chunk,
                    session = session,
                    responseIdRef = responseIdRef,
                    responseCreatedBuffered = responseCreatedBuffered,
                    latestUsage = latestUsage,
                    functionCallDedup = functionCallDedup,
                    openMessageItemId = openMessageItemId,
                    openMessageItemIndex = openMessageItemIndex,
                    openMessageBuffer = openMessageBuffer,
                    specModel = spec.model,
                )
            }
            .concatWith(
                Mono.fromCallable {
                    val tailEmissions = mutableListOf<StepEmission>()
                    // 라운드 끝에 열려 있는 메시지가 있으면 닫음.
                    closeOpenMessage(
                        session = session,
                        openMessageItemId = openMessageItemId,
                        openMessageItemIndex = openMessageItemIndex,
                        openMessageBuffer = openMessageBuffer,
                        emissions = tailEmissions,
                    )
                    val completed = buildRoundCompleted(
                        responseId = responseIdRef.get(),
                        usage = latestUsage.get(),
                        sequence = session.nextSequence.getAndIncrement(),
                    )
                    tailEmissions.add(StepEmission.EndOfRound(completed))
                    tailEmissions.toList()
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
                            pendingFunctionResponses = pendingFunctionResponses,
                            pendingFunctionCalls = pendingFunctionCalls,
                            hadReturnDirect = hadReturnDirect,
                        ).cast(Any::class.java)
                    }
                    is StepEmission.EndOfRound -> {
                        if (hadReturnDirect.get()) {
                            session.finalizeSilently()
                        } else if (pendingFunctionResponses.isEmpty()) {
                            session.finalize(unit.completed)
                        } else {
                            session.absorbRoundCompleted(unit.completed)
                            state.contents.add(buildModelFunctionCallContent(pendingFunctionCalls))
                            state.contents.add(buildFunctionResponseContent(pendingFunctionResponses))
                            executeStep(
                                spec = spec,
                                state = state,
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
                        logger.warn(error) { "google stream error" }
                        session.error(error)
                    }
                },
                { /* completion handled inside EndOfRound branch */ },
            )

        currentSubscription.getAndSet(subscription)?.dispose()
    }

    private fun buildChunkEmissions(
        chunk: GoogleStreamChunk,
        session: StreamSession,
        responseIdRef: AtomicReference<String>,
        responseCreatedBuffered: AtomicBoolean,
        latestUsage: AtomicReference<GoogleUsage?>,
        functionCallDedup: MutableSet<String>,
        openMessageItemId: AtomicReference<String?>,
        openMessageItemIndex: AtomicReference<Long?>,
        openMessageBuffer: StringBuilder,
        specModel: String,
    ): List<StepEmission> {
        val emissions = mutableListOf<StepEmission>()

        if (responseCreatedBuffered.compareAndSet(false, true)) {
            val responseId = chunk.responseId ?: responseIdRef.get()
            responseIdRef.set(responseId)
            emissions.add(
                StepEmission.PassThrough(
                    LlmStreamEvent.ResponseCreated(
                        id = responseId,
                        sequence = session.nextSequence.getAndIncrement(),
                        model = chunk.modelVersion ?: specModel,
                    )
                )
            )
        }
        chunk.usage?.let { latestUsage.set(it) }

        for (part in chunk.parts) {
            when (part) {
                is GoogleStreamPart.Text -> {
                    if (part.thought) {
                        // thoughts 는 일단 스킵 (후속 작업에서 reasoning 으로 매핑 가능)
                        continue
                    }
                    val text = part.text
                    if (text.isEmpty()) continue

                    if (openMessageItemId.get() == null) {
                        // 새 메시지 아이템 열기
                        val itemId = "msg-${UUID.randomUUID()}"
                        val itemIndex = session.nextItemIndex.getAndIncrement()
                        openMessageItemId.set(itemId)
                        openMessageItemIndex.set(itemIndex)
                        emissions.add(
                            StepEmission.PassThrough(
                                LlmStreamEvent.ResponseMessageAdded(
                                    itemId = itemId,
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = itemIndex,
                                )
                            )
                        )
                        emissions.add(
                            StepEmission.PassThrough(
                                LlmStreamEvent.MessageContentPartAdded(
                                    itemId = itemId,
                                    sequence = session.nextSequence.getAndIncrement(),
                                    itemIndex = itemIndex,
                                    partIndex = 0L,
                                )
                            )
                        )
                    }
                    val itemId = openMessageItemId.get()!!
                    val itemIndex = openMessageItemIndex.get()!!
                    openMessageBuffer.append(text)
                    emissions.add(
                        StepEmission.PassThrough(
                            LlmStreamEvent.MessageDelta(
                                itemId = itemId,
                                sequence = session.nextSequence.getAndIncrement(),
                                itemIndex = itemIndex,
                                partIndex = 0L,
                                delta = text,
                            )
                        )
                    )
                }

                is GoogleStreamPart.FunctionCall -> {
                    // 함수 호출 발생 시점에 열려 있는 메시지 아이템을 먼저 닫는다.
                    closeOpenMessage(
                        session = session,
                        openMessageItemId = openMessageItemId,
                        openMessageItemIndex = openMessageItemIndex,
                        openMessageBuffer = openMessageBuffer,
                        emissions = emissions,
                    )

                    val argumentsJson = jsonMapper.writeValueAsString(part.call.args)
                    val signature = "${part.call.name}:$argumentsJson"
                    if (functionCallDedup.add(signature)) {
                        val pending = PendingFunctionCall(
                            id = part.call.id,
                            name = part.call.name,
                            arguments = part.call.args,
                            argumentsJson = argumentsJson,
                            requestSequence = session.nextSequence.getAndIncrement(),
                        )
                        emissions.add(StepEmission.ToolInvocation(pending))
                    }
                }
            }
        }
        return emissions
    }

    private fun closeOpenMessage(
        session: StreamSession,
        openMessageItemId: AtomicReference<String?>,
        openMessageItemIndex: AtomicReference<Long?>,
        openMessageBuffer: StringBuilder,
        emissions: MutableList<StepEmission>,
    ) {
        val itemId = openMessageItemId.get() ?: return
        val itemIndex = openMessageItemIndex.get() ?: return
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.MessageContentPartDone(
                    itemId = itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = itemIndex,
                    partIndex = 0L,
                    text = openMessageBuffer.toString(),
                )
            )
        )
        emissions.add(
            StepEmission.PassThrough(
                LlmStreamEvent.ResponseMessageDone(
                    itemId = itemId,
                    sequence = session.nextSequence.getAndIncrement(),
                    itemIndex = itemIndex,
                )
            )
        )
        openMessageItemId.set(null)
        openMessageItemIndex.set(null)
        openMessageBuffer.setLength(0)
    }

    private fun buildRoundCompleted(
        responseId: String,
        usage: GoogleUsage?,
        sequence: Long,
    ): LlmStreamEvent.ResponseCompleted? {
        if (usage == null) return null
        return LlmStreamEvent.ResponseCompleted(
            id = responseId,
            sequence = sequence,
            inputTokens = usage.promptTokenCount,
            cachedTokens = usage.cachedContentTokenCount,
            outputTokens = usage.candidatesTokenCount,
            reasoningTokens = usage.thoughtsTokenCount,
            totalTokens = usage.totalTokenCount,
        )
    }

    private fun handleToolInline(
        call: PendingFunctionCall,
        handlers: Map<String, ToolHandler>,
        session: StreamSession,
        pendingFunctionResponses: MutableList<FunctionResponsePayload>,
        pendingFunctionCalls: MutableList<PendingFunctionCall>,
        hadReturnDirect: AtomicBoolean,
    ): Mono<Unit> {
        return invokeTool(call, handlers)
            .doOnNext { result ->
                emitFunctionCallDone(session, result)
                if (result.directResult != null) {
                    hadReturnDirect.set(true)
                    emitReturnDirectMessage(session, result.directResult)
                } else if (result.functionResponse != null) {
                    pendingFunctionResponses.add(result.functionResponse)
                    pendingFunctionCalls.add(call)
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
        val handler = handlers[call.name]
            ?: return Mono.error(IllegalStateException("No tool callback registered for ${call.name}"))

        val parsedInput = try {
            jsonMapper.readValue(call.argumentsJson, handler.inputJavaType)
        } catch (ex: Exception) {
            return Mono.error(ToolArgumentsParseException(call.name, ex))
        }

        val publisher: Publisher<Any> = try {
            @Suppress("UNCHECKED_CAST")
            handler.callback.callback(parsedInput) as Publisher<Any>
        } catch (ex: Exception) {
            return Mono.error(ex)
        }

        return Flux.from(publisher)
            .switchIfEmpty(Flux.error(IllegalStateException("Tool ${call.name} returned no result")))
            .single()
            .map { nonNullResult ->
                val serializedOutput = serializeToolResult(nonNullResult)
                if (handler.callback.returnDirect) {
                    ToolInvocationResult(
                        request = call,
                        functionResponse = null,
                        directResult = DirectToolResult(
                            callId = call.id ?: "return-direct",
                            output = serializedOutput,
                        ),
                        output = serializedOutput,
                        delivery = LlmStreamEvent.FunctionCallObserved.Delivery.RETURN_DIRECT,
                    )
                } else {
                    ToolInvocationResult(
                        request = call,
                        functionResponse = FunctionResponsePayload(
                            id = call.id,
                            name = call.name,
                            response = mapOf("output" to normalizeToolOutput(nonNullResult)),
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
                callId = result.request.id,
                toolName = result.request.name,
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
                callId = request.id,
                toolName = request.name,
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

    private fun buildInitialContents(inputs: List<LlmMessageInputItem>): List<GoogleContent> {
        return inputs.map { item ->
            when (item.role) {
                LlmMessageInputItem.Role.USER -> GoogleContent(
                    role = "user",
                    parts = listOf(GooglePart.Text(item.content)),
                )

                LlmMessageInputItem.Role.ASSISTANT -> GoogleContent(
                    role = "model",
                    parts = listOf(GooglePart.Text(item.content)),
                )

                LlmMessageInputItem.Role.SYSTEM,
                LlmMessageInputItem.Role.DEVELOPER,
                -> throw UnsupportedInputTypeException(item.role.name)
            }
        }
    }

    private fun buildModelFunctionCallContent(calls: List<PendingFunctionCall>): GoogleContent {
        return GoogleContent(
            role = "model",
            parts = calls.map { call ->
                GooglePart.FunctionCall(
                    id = call.id,
                    name = call.name,
                    args = call.arguments,
                )
            },
        )
    }

    private fun buildFunctionResponseContent(responses: List<FunctionResponsePayload>): GoogleContent {
        return GoogleContent(
            role = "user",
            parts = responses.map { payload ->
                GooglePart.FunctionResponse(
                    id = payload.id,
                    name = payload.name,
                    response = payload.response,
                )
            }
        )
    }

    private fun mapFunctionCallingMode(choice: ResponseCreateParams.ToolChoice?): GoogleFunctionCallingMode? {
        if (choice == null) {
            return null
        }
        return choice.accept(
            object : ResponseCreateParams.ToolChoice.Visitor<GoogleFunctionCallingMode?> {
                override fun visitOptions(options: ToolChoiceOptions): GoogleFunctionCallingMode? {
                    return when (options.value()) {
                        ToolChoiceOptions.Value.NONE -> GoogleFunctionCallingMode.NONE
                        ToolChoiceOptions.Value.AUTO -> GoogleFunctionCallingMode.AUTO
                        ToolChoiceOptions.Value.REQUIRED -> GoogleFunctionCallingMode.ANY
                        ToolChoiceOptions.Value._UNKNOWN -> GoogleFunctionCallingMode.AUTO
                    }
                }

                override fun visitAllowed(allowed: ToolChoiceAllowed): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitTypes(types: ToolChoiceTypes): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitFunction(function: ToolChoiceFunction): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitMcp(mcp: ToolChoiceMcp): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitCustom(custom: ToolChoiceCustom): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitApplyPatch(applyPatch: ToolChoiceApplyPatch): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }

                override fun visitShell(shell: ToolChoiceShell): GoogleFunctionCallingMode? {
                    return GoogleFunctionCallingMode.ANY
                }
            }
        )
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

        return EffectiveRequest(
            instructions = override.instructions ?: base.instructions,
            inputs = (override.inputs ?: base.inputs ?: emptyList()).map { input ->
                when (input) {
                    is LlmMessageInputItem -> input
                    else -> throw UnsupportedInputTypeException(input.type.name)
                }
            },
            model = override.modelKey ?: base.modelKey ?: DEFAULT_MODEL,
            temperature = override.options?.temperature ?: base.options?.temperature,
            topP = override.options?.topP ?: base.options?.topP,
            topK = override.options?.topK ?: base.options?.topK,
            includeThoughts = override.options?.includeThoughts ?: base.options?.includeThoughts,
            maxTokens = override.options?.maxTokens ?: base.options?.maxTokens,
            toolChoice = override.toolChoice ?: base.toolChoice,
            toolDefinitions = toolDefinitions,
            toolHandlers = toolHandlers,
            maxBufferedEvents = effectiveMaxBufferedEvents(override.options, base.options),
        )
    }

    private fun List<LlmToolSpec>.requireFunctionToolsOnly(provider: String): List<ToolCallback<*, *>> {
        val unsupported = filterNot { it is ToolCallback<*, *> }
        if (unsupported.isNotEmpty()) {
            throw UnsupportedToolException(provider, unsupported.map { it.toolType })
        }
        return filterIsInstance<ToolCallback<*, *>>()
    }

    private fun serializeToolResult(result: Any?): String {
        return when (result) {
            null -> "null"
            is String -> result
            is Number, is Boolean -> result.toString()
            else -> jsonMapper.writeValueAsString(result)
        }
    }

    private fun normalizeToolOutput(result: Any?): Any {
        return when (result) {
            null -> "null"
            is String, is Number, is Boolean, is Map<*, *>, is List<*> -> result
            else -> jsonMapper.convertValue(result, Any::class.java)
        }
    }

    private data class EffectiveRequest(
        val instructions: String?,
        val inputs: List<LlmMessageInputItem>,
        val model: String,
        val temperature: Double?,
        val topP: Double?,
        val topK: Double?,
        val includeThoughts: Boolean?,
        val maxTokens: Long?,
        val toolChoice: ResponseCreateParams.ToolChoice?,
        val toolDefinitions: List<ToolDefinition>,
        val toolHandlers: Map<String, ToolHandler>,
        val maxBufferedEvents: Int,
    )

    private data class PendingFunctionCall(
        override val callId: String? = null,
        val id: String?,
        val name: String,
        val arguments: Map<String, Any>,
        override val argumentsJson: String,
        override val requestSequence: Long? = null,
    ) : PendingFunctionCallContract {
        override val toolName: String get() = name
    }

    private data class ToolHandler(
        val callback: ToolCallback<Any, Any?>,
        val inputJavaType: Class<*>,
    )

    private data class FunctionResponsePayload(
        val id: String?,
        val name: String,
        val response: Map<String, Any>,
    )

    private data class DirectToolResult(
        val callId: String,
        val output: String,
    )

    private data class ToolInvocationResult(
        val request: PendingFunctionCall,
        val functionResponse: FunctionResponsePayload?,
        val directResult: DirectToolResult?,
        val output: String,
        val delivery: LlmStreamEvent.FunctionCallObserved.Delivery,
    )

    private data class ConversationState(
        val contents: MutableList<GoogleContent>,
    )

    private companion object {
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}