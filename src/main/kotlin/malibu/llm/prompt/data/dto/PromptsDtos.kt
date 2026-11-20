package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.PromptRefType
import java.time.Instant
import java.util.UUID

data class PromptSearchSpec(
    val workspaceId: UUID? = null,
    val name: String? = null,
)

data class PromptRefSearchSpec(
    val promptId: UUID? = null,
    val type: PromptRefType? = null,
    val name: String? = null,
)

/** POST /workspaces/{workspaceId}/prompts */
data class PromptCreateRequest(
    val name: String,
    val description: String? = null,
    val instructions: String,
    val additionalInputs: List<AdditionalInputRequest> = emptyList(),
    val mainRefName: String = "main",
)

data class PromptUpdateRequest(
    @field:Schema(description = "이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
    val description: Patch<String?> = Patch.Absent,
    @field:Schema(description = "default branch 로 지정할 BRANCH ref ID. 프롬프트에는 항상 default branch 가 있어야 하므로 지울 수 없다. 보내지 않거나 null 이면 변경 없음.")
    val defaultBranchRefId: Long? = null,
    @field:Schema(description = "latest tag 로 지정할 TAG ref ID. 보내지 않으면 변경 없음, null 이면 latest tag 를 해제한다. 보내면(null 포함) prompt_ref:publish 권한이 필요하다.")
    val latestTagRefId: Patch<Long?> = Patch.Absent,
)

data class AdditionalInputRequest(
    val position: Int?,
    val role: InputRole,
    val message: String,
)

data class PromptRefCreateRequest(
    val name: String,
    val type: PromptRefType,
    val fromRefId: Long? = null,
    val description: String? = null,
)

data class PromptRefUpdateRequest(
    @field:Schema(description = "ref 이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
    val description: Patch<String?> = Patch.Absent,
    @field:Schema(description = "instructions 본문. 보내지 않거나 null 이면 변경 없음. 빈 문자열을 보내면 빈 값으로 바뀐다.")
    val instructions: String? = null,
)

data class PromptResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val workspaceId: UUID,
    val defaultBranchRefId: Long?,
    val latestTagRefId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class PromptRefResponse(
    val id: Long,
    val promptId: UUID,
    val name: String,
    val type: PromptRefType,
    val instructions: String,
    val description: String?,
    val sourceTagRefId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class AdditionalInputItemResponse(
    val id: Long,
    val position: Int,
    val role: InputRole,
    val message: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class AdditionalInputCreateRequest(
    val position: Int?,
    val role: InputRole,
    val message: String,
)

data class AdditionalInputUpdateRequest(
    @field:Schema(description = "순서(1부터). 범위를 벗어나면 맨 앞 / 맨 뒤로 맞춘다. 바꾸면 나머지 항목의 순서가 다시 매겨진다. 보내지 않거나 null 이면 변경 없음.")
    val position: Int? = null,
    @field:Schema(description = "입력 역할. 보내지 않거나 null 이면 변경 없음.")
    val role: InputRole? = null,
    @field:Schema(description = "메시지 본문. 보내지 않거나 null 이면 변경 없음.")
    val message: String? = null,
)
