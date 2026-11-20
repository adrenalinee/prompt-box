package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(description = "벤더 이름. 서버의 벤더 클라이언트 이름(openai / google / xai)과 같아야 호출에 쓸 수 있다. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
    val description: Patch<String?> = Patch.Absent,
)

data class LlmVendorResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
