package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(description = "벤더 ID. 보내지 않거나 null 이면 변경 없음.")
    val vendorId: UUID? = null,
    @field:Schema(description = "이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "새 API 키 값. 보내면 키를 교체한다. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val value: String? = null,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
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
