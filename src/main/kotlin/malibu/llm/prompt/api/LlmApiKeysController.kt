package malibu.llm.prompt.api

import malibu.llm.prompt.data.dto.LlmApiKeyCreateRequest
import malibu.llm.prompt.data.dto.LlmApiKeyResponse
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.dto.LlmApiKeyUpdateRequest
import malibu.llm.prompt.data.entity.LlmApiKey
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
import java.util.UUID

@RestController
@RequestMapping("/llm-api-keys")
class LlmApiKeysController(
    private val llmCatalogService: LlmCatalogService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: LlmApiKeyCreateRequest): LlmApiKeyResponse {
        val apiKey = llmCatalogService.createApiKey(
            workspaceId = request.workspaceId,
            vendorId = request.vendorId,
            name = request.name,
            value = request.value,
            description = request.description,
        )
        return apiKey.toResponse()
    }

    @GetMapping
    fun list(
        @ParameterObject searchSpec: LlmApiKeySearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmApiKeyResponse> =
        llmCatalogService.listApiKeys(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/{apiKeyId}")
    fun get(@PathVariable apiKeyId: UUID): LlmApiKeyResponse {
        return llmCatalogService.getApiKey(apiKeyId).toResponse()
    }

    @PatchMapping("/{apiKeyId}")
    fun update(
        @PathVariable apiKeyId: UUID,
        @RequestBody request: LlmApiKeyUpdateRequest,
    ): LlmApiKeyResponse {
        return llmCatalogService.updateApiKey(
            apiKeyId = apiKeyId,
            workspaceId = request.workspaceId,
            vendorId = request.vendorId,
            name = request.name,
            value = request.value,
            description = request.description,
        ).toResponse()
    }

    @DeleteMapping("/{apiKeyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable apiKeyId: UUID) {
        llmCatalogService.deleteApiKey(apiKeyId)
    }
}

fun LlmApiKey.toResponse() = LlmApiKeyResponse(
    id = requireNotNull(id),
    workspaceId = requireNotNull(workspace.id),
    vendorId = requireNotNull(llmVendor.id),
    name = name,
    last5 = last5,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
