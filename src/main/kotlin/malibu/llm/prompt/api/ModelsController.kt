package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.data.dto.LlmModelCreateRequest
import malibu.llm.prompt.data.dto.LlmModelResponse
import malibu.llm.prompt.data.dto.LlmModelSearchSpec
import malibu.llm.prompt.data.dto.LlmModelUpdateRequest
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.service.LlmCatalogService
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "LLM 모델", description = "벤더별 모델 카탈로그. 플랫폼 전체가 공유한다. modelKey 는 벤더 API 에 보내는 모델 식별자이고, modelType(STANDARD / REASONING)에 따라 호출 때 적용되는 옵션이 달라진다. 조회는 로그인한 사용자 누구나, 변경은 플랫폼 권한이 필요하다.")
@RestController
@RequestMapping("/models")
class ModelsController(
    private val llmCatalogService: LlmCatalogService,
) {

    @PostMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_MODEL_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "모델 생성")
    fun create(@RequestBody request: LlmModelCreateRequest): LlmModelResponse {
        val model = llmCatalogService.createModel(
            vendorId = request.vendorId,
            name = request.name,
            modelKey = request.modelKey,
            modelType = request.modelType,
            contextWindow = request.contextWindow,
            inputPrice = request.inputPrice,
            outputPrice = request.outputPrice,
            description = request.description,
        )
        return model.toResponse()
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "모델 목록")
    fun list(
        @ParameterObject searchSpec: LlmModelSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmModelResponse> =
        llmCatalogService.listModels(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/{modelId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "모델 조회")
    fun get(@PathVariable modelId: Long): LlmModelResponse {
        return llmCatalogService.getModel(modelId).toResponse()
    }

    @PatchMapping("/{modelId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_MODEL_MANAGE}')")
    @Operation(summary = "모델 수정")
    fun update(
        @PathVariable modelId: Long,
        @RequestBody request: LlmModelUpdateRequest,
    ): LlmModelResponse {
        return llmCatalogService.updateModel(
            modelId = modelId,
            vendorId = request.vendorId,
            name = request.name,
            modelKey = request.modelKey,
            modelType = request.modelType,
            contextWindow = request.contextWindow,
            inputPrice = request.inputPrice,
            outputPrice = request.outputPrice,
            description = request.description,
        ).toResponse()
    }

    @DeleteMapping("/{modelId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_MODEL_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "모델 삭제")
    fun delete(@PathVariable modelId: Long) {
        llmCatalogService.deleteModel(modelId)
    }
}

fun LlmModel.toResponse() = LlmModelResponse(
    id = id,
    name = name,
    modelKey = modelKey,
    modelType = modelType,
    vendorId = requireNotNull(llmVendor.id),
    contextWindow = contextWindow,
    inputPrice = inputPrice,
    outputPrice = outputPrice,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
