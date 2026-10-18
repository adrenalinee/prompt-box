package malibu.llm.prompt.data.dto

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

data class PromptCreateRequest(
    val workspaceId: UUID,
    val name: String,
    val description: String? = null,
    val instructions: String,
    val additionalInputs: List<AdditionalInputRequest> = emptyList(),
    val mainRefName: String = "main",
)

data class PromptUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val defaultBranchRefId: Long? = null,
    val latestTagRefId: Long? = null,
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
    val name: String? = null,
    val description: String? = null,
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
    val position: Int? = null,
    val role: InputRole? = null,
    val message: String? = null,
)
