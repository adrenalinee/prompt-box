package malibu.llm.prompt.api

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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/models")
class ModelsController(
    private val llmCatalogService: LlmCatalogService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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
    fun list(
        @ParameterObject searchSpec: LlmModelSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmModelResponse> =
        llmCatalogService.listModels(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/{modelId}")
    fun get(@PathVariable modelId: Long): LlmModelResponse {
        return llmCatalogService.getModel(modelId).toResponse()
    }

    @PatchMapping("/{modelId}")
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
