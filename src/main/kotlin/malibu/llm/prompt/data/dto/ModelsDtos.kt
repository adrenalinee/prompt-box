package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(description = "벤더 ID. 보내지 않거나 null 이면 변경 없음.")
    val vendorId: UUID? = null,
    @field:Schema(description = "표시 이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "벤더 API 에 보내는 모델 식별자. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val modelKey: String? = null,
    @field:Schema(description = "모델 타입. 호출 때 적용되는 옵션이 달라진다. 보내지 않거나 null 이면 변경 없음.")
    val modelType: LlmModelType? = null,
    @field:Schema(description = "컨텍스트 윈도우(토큰). 보내지 않으면 변경 없음, null 이면 지운다.")
    val contextWindow: Patch<Long?> = Patch.Absent,
    @field:Schema(description = "입력 가격. 보내지 않으면 변경 없음, null 이면 지운다.")
    val inputPrice: Patch<Long?> = Patch.Absent,
    @field:Schema(description = "출력 가격. 보내지 않으면 변경 없음, null 이면 지운다.")
    val outputPrice: Patch<Long?> = Patch.Absent,
    @field:Schema(description = "설명. 보내지 않으면 변경 없음, null 이면 지운다.")
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
