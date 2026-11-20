package malibu.llm.prompt.data.dto

import java.time.Instant
import java.util.UUID

data class LlmApiKeySearchSpec(
    val workspaceId: UUID? = null,
    val vendorId: UUID? = null,
    val name: String? = null,
)

/** POST /workspaces/{workspaceId}/llm-api-keys */
data class LlmApiKeyCreateRequest(
    val vendorId: UUID,
    val name: String,
    val value: String,
    val description: String? = null,
)

/** workspace 이동은 할 수 없다. */
data class LlmApiKeyUpdateRequest(
    val vendorId: UUID? = null,
    val name: String? = null,
    val value: String? = null,
    /** null 이면 지운다. */
    val description: Patch<String?> = Patch.Absent,
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
