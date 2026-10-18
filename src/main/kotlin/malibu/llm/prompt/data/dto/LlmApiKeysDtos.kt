package malibu.llm.prompt.data.dto

import java.time.Instant
import java.util.UUID

data class LlmApiKeySearchSpec(
    val workspaceId: UUID? = null,
    val vendorId: UUID? = null,
    val name: String? = null,
)

data class LlmApiKeyCreateRequest(
    val workspaceId: UUID,
    val vendorId: UUID,
    val name: String,
    val value: String,
    val description: String? = null,
)

data class LlmApiKeyUpdateRequest(
    val workspaceId: UUID? = null,
    val vendorId: UUID? = null,
    val name: String? = null,
    val value: String? = null,
    val description: String? = null,
)

data class LlmApiKeyResponse(
    val id: UUID,
    val workspaceId: UUID,
    val vendorId: UUID,
    val name: String,
    val last5: String?,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
