package malibu.llm.prompt.data.dto

import malibu.llm.prompt.data.LlmModelType
import java.time.Instant
import java.util.UUID

data class LlmModelSearchSpec(
    val vendorId: UUID? = null,
    val name: String? = null,
)

data class LlmModelCreateRequest(
    val vendorId: UUID,
    val name: String,
    val modelKey: String,
    val modelType: LlmModelType = LlmModelType.STANDARD,
    val contextWindow: Long? = null,
    val inputPrice: Long? = null,
    val outputPrice: Long? = null,
    val description: String? = null,
)

data class LlmModelUpdateRequest(
    val vendorId: UUID? = null,
    val name: String? = null,
    val modelKey: String? = null,
    val modelType: LlmModelType? = null,
    /** 아래 nullable 필드는 null 이면 지운다. */
    val contextWindow: Patch<Long?> = Patch.Absent,
    val inputPrice: Patch<Long?> = Patch.Absent,
    val outputPrice: Patch<Long?> = Patch.Absent,
    val description: Patch<String?> = Patch.Absent,
)

data class LlmModelResponse(
    val id: Long,
    val name: String,
    val modelKey: String,
    val modelType: LlmModelType,
    val vendorId: UUID,
    val contextWindow: Long?,
    val inputPrice: Long?,
    val outputPrice: Long?,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
