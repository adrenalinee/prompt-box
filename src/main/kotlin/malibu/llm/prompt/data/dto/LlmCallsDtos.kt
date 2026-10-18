package malibu.llm.prompt.data.dto

import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.OutputItemPartType
import malibu.llm.prompt.data.OutputItemType
import java.time.Instant
import java.util.UUID

data class LlmCallSearchSpec(
    val workspaceId: UUID? = null,
    val modelId: Long? = null,
    val status: LlmCallStatus? = null,
    val promptId: UUID? = null,
)

data class LlmCallCancelRequest(
    val reason: String? = null,
)

data class LlmCallLogResponse(
    val id: UUID,
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,
    val promptRefId: Long?,
    val elapsed: Long,
    val status: LlmCallStatus,
    val errorCode: String?,
    val instructionsPreview: String?,
    val createdAt: Instant?,
)

data class LlmCallLogDetailResponse(
    val callLog: LlmCallLogDetailInfo,
    val inputItems: List<InputItemResponse>,
    val outputItems: List<OutputItemResponse>,
)

data class LlmCallLogDetailInfo(
    val id: UUID,
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,
    val promptRefId: Long?,
    val elapsed: Long,
    val status: LlmCallStatus,
    val errorCode: String?,
    val errorMessage: String?,
    val llmCallOptions: String?,
    val instructions: String,
    val determinedModel: String?,
    val inputTokens: Long?,
    val cachedTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val totalTokens: Long?,
    val createdAt: Instant?,
)

data class InputItemResponse(
    val id: Long,
    val position: Int,
    val role: InputRole,
    val renderedMessage: String,
    val createdAt: Instant?,
)

data class OutputItemResponse(
    val id: Long,
    val position: Int,
    val itemType: OutputItemType,
    val parts: List<OutputItemPartResponse>,
    val createdAt: Instant?,
)

data class OutputItemPartResponse(
    val id: Long,
    val partType: OutputItemPartType,
    val partIndex: Long,
    val text: String?,
    val createdAt: Instant?,
)