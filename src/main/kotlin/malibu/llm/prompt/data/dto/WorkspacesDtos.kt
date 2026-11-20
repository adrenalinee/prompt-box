package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
import malibu.llm.prompt.data.InputRole
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.VerbosityType
import java.time.Instant
import java.util.UUID

data class WorkspaceSearchSpec(
    val name: String? = null,
    val organizationId: UUID? = null,
)

/** POST /organizations/{organizationId}/workspaces */
data class WorkspaceCreateRequest(
    val name: String,
    val description: String? = null,
    val defaultModelId: Long? = null,
)

data class WorkspaceUpdateRequest(
    @field:Schema(description = "이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
    val description: Patch<String?> = Patch.Absent,
    @field:Schema(description = "기본 모델 ID. 보내지 않으면 변경 없음, null 이면 기본 모델을 해제한다.")
    val defaultModelId: Patch<Long?> = Patch.Absent,
)

data class WorkspaceResponse(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val description: String?,
    val defaultModelId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class WorkspaceDetailResponse(
    val id: UUID,
    val organizationId: UUID,
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
