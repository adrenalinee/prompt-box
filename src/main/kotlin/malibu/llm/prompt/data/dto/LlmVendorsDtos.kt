package malibu.llm.prompt.data.dto

import java.time.Instant
import java.util.UUID

data class LlmVendorSearchSpec(
    val name: String? = null,
)

data class LlmVendorCreateRequest(
    val name: String,
    val description: String? = null,
)

data class LlmVendorUpdateRequest(
    val name: String? = null,
    /** null 이면 지운다. */
    val description: Patch<String?> = Patch.Absent,
)

data class LlmVendorResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
