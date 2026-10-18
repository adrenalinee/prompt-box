package malibu.llm.prompt.data.dto

import malibu.llm.prompt.data.InputRole
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.VerbosityType
import java.time.Instant
import java.util.UUID

data class WorkspaceSearchSpec(
    val name: String? = null,
)

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
    val role: InputRole,
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
