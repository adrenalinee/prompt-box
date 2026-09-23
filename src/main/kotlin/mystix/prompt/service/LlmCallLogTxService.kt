package mystix.prompt.service

import mystix.prompt.ExecuteStreamingOrchestratorImpl
import mystix.prompt.data.LlmCallStatus
import mystix.prompt.data.OutputItemPartType
import mystix.prompt.data.OutputItemType
import mystix.prompt.data.entity.InputItem
import mystix.prompt.data.entity.LlmCallLog
import mystix.prompt.data.entity.OutputItem
import mystix.prompt.data.entity.OutputItemPart
import mystix.prompt.data.repo.InputItemRepository
import mystix.prompt.data.repo.LlmCallLogRepository
import mystix.prompt.data.repo.OutputItemPartRepository
import mystix.prompt.data.repo.OutputItemRepository
import mystix.prompt.error.CallLogNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class LlmCallLogTxService(
    private val llmCallLogRepository: LlmCallLogRepository,
    private val inputItemRepository: InputItemRepository,
    private val outputItemRepository: OutputItemRepository,
    private val outputItemPartRepository: OutputItemPartRepository,
) {
    @Transactional
    fun createLog(
        workspace: mystix.prompt.data.entity.Workspace,
        model: mystix.prompt.data.entity.LlmModel,
        apiKey: mystix.prompt.data.entity.LlmApiKey,
        promptRef: mystix.prompt.data.entity.PromptRef?,
        instructions: String,
        llmCallOptions: String?,
        messages: List<ExecuteStreamingOrchestratorImpl.RenderedInput>,
    ): LlmCallLog {
        val callLog = llmCallLogRepository.save(
            LlmCallLog(
                llmModel = model,
                llmApiKey = apiKey,
                promptRef = promptRef,
                instructions = instructions,
                elapsed = 0,
                status = LlmCallStatus.PENDING,
                llmCallOptions = llmCallOptions,
                workspace = workspace,
            )
        )

        messages.forEachIndexed { index, message ->
            inputItemRepository.save(
                InputItem(
                    llmCallLog = callLog,
                    position = index + 1,
                    role = message.role,
                    renderedMessage = message.message,
                )
            )
        }

        return callLog
    }

    @Transactional
    fun updateSuccessLog(
        callLogId: UUID,
        elapsedMs: Long,
        outputItems: List<OutputItemPayload>,
        determinedModel: String,
        tokenUsage: TokenUsagePayload?,
    ) {
        val callLog = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        callLog.elapsed = elapsedMs
        callLog.determinedModel = determinedModel
        callLog.status = LlmCallStatus.SUCCESS
        callLog.errorCode = null
        callLog.errorMessage = null
        callLog.determinedModel = determinedModel
        if (tokenUsage != null) {
            callLog.inputTokens = tokenUsage.inputTokens
            callLog.cachedTokens = tokenUsage.cachedTokens
            callLog.outputTokens = tokenUsage.outputTokens
            callLog.reasoningTokens = tokenUsage.reasoningTokens
            callLog.totalTokens = tokenUsage.totalTokens
        }
        llmCallLogRepository.save(callLog)

        saveInputItems(callLog, outputItems)
    }

    @Transactional
    fun updateRunningLog(callLogId: UUID) {
        val callLog = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        callLog.status = LlmCallStatus.RUNNING
        llmCallLogRepository.save(callLog)
    }

    @Transactional
    fun updateErrorLog(
        callLogId: UUID, elapsedMs: Long,
        outputItems: List<OutputItemPayload>,
        determinedModel: String?,
        errorMessage: String
    ) {
        val callLog = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        callLog.elapsed = elapsedMs
        callLog.determinedModel = determinedModel
        callLog.status = LlmCallStatus.ERROR
        callLog.errorMessage = errorMessage

        saveInputItems(callLog, outputItems)
        llmCallLogRepository.save(callLog)
    }

//    @Transactional
//    fun updateTimeoutLog(
//        callLogId: UUID, elapsedMs: Long,
//        outputItems: List<OutputItemPayload>,
//        determinedModel: String?,
//        errorMessage: String
//    ) {
//        val callLog = llmCallLogRepository.findById(callLogId)
//            .orElseThrow { CallLogNotFoundException(callLogId) }
//        callLog.elapsed = elapsedMs
//        callLog.determinedModel = determinedModel
//        callLog.status = LlmCallStatus.TIMEOUT
//        callLog.errorMessage = errorMessage
//
//        saveInputItems(callLog, outputItems)
//        llmCallLogRepository.save(callLog)
//    }

    @Transactional
    fun updateCancelRequested(callLogId: UUID, reason: String?) {
        val callLog = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        if (
            callLog.status == LlmCallStatus.CANCELLED ||
            callLog.status == LlmCallStatus.SUCCESS ||
            callLog.status == LlmCallStatus.ERROR ||
            callLog.status == LlmCallStatus.TIMEOUT
        ) {
            return
        }
        callLog.status = LlmCallStatus.CANCEL_REQUESTED
        if (!reason.isNullOrBlank()) {
            callLog.errorMessage = reason
        }
        llmCallLogRepository.save(callLog)
    }

    @Transactional
    fun updateCancelledLog(
        callLogId: UUID, elapsedMs: Long,
        outputItems: List<OutputItemPayload>,
        determinedModel: String?,
        errorMessage: String?
    ) {
        val callLog = llmCallLogRepository.findById(callLogId)
            .orElseThrow { CallLogNotFoundException(callLogId) }
        callLog.elapsed = elapsedMs
        callLog.determinedModel = determinedModel
        callLog.status = LlmCallStatus.CANCELLED
        callLog.errorMessage = errorMessage

        saveInputItems(callLog, outputItems)
        llmCallLogRepository.save(callLog)
    }

    private fun saveInputItems(callLog: LlmCallLog, outputItems: List<OutputItemPayload>) {
        if (outputItems.isEmpty()) {
            return
        }

        outputItems.forEachIndexed { index, item ->
            val outputItem = outputItemRepository.save(
                OutputItem(
                    llmCallLog = callLog,
                    position = index + 1,
                    itemType = item.itemType,
                )
            )
            if (item.parts.isNotEmpty()) {
                item.parts.forEach { part ->
                    outputItemPartRepository.save(
                        OutputItemPart(
                            outputItem = outputItem,
                            partType = part.partType,
                            partIndex = part.partIndex,
                            text = part.text,
                        )
                    )
                }
            }
        }
    }

    data class OutputItemPayload(
        val itemType: OutputItemType,
//        val outputText: String,
        val parts: List<OutputItemPartPayload> = emptyList(),
    )

    data class OutputItemPartPayload(
        val partType: OutputItemPartType,
        val partIndex: Long,
//        val sequence: Long,
        val text: String? = null,
    )

    data class TokenUsagePayload(
        val inputTokens: Long?,
        val cachedTokens: Long?,
        val outputTokens: Long?,
        val reasoningTokens: Long?,
        val totalTokens: Long?,
    )
}
