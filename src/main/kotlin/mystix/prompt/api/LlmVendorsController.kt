package mystix.prompt.api

import mystix.prompt.data.entity.LlmVendor
import mystix.prompt.service.LlmCatalogService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/llm-vendors")
class LlmVendorsController(
    private val llmCatalogService: LlmCatalogService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmVendorResponse> =
        llmCatalogService.listVendors(name, pageable).toResponse { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: LlmVendorCreateRequest): LlmVendorResponse {
        val vendor = llmCatalogService.createVendor(request.name, request.description)
        return vendor.toResponse()
    }

    @GetMapping("/{vendorId}")
    fun get(@PathVariable vendorId: UUID): LlmVendorResponse {
        return llmCatalogService.getVendor(vendorId).toResponse()
    }

    @PatchMapping("/{vendorId}")
    fun update(
        @PathVariable vendorId: UUID,
        @RequestBody request: LlmVendorUpdateRequest,
    ): LlmVendorResponse {
        return llmCatalogService.updateVendor(vendorId, request.name, request.description).toResponse()
    }

    @DeleteMapping("/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable vendorId: UUID) {
        llmCatalogService.deleteVendor(vendorId)
    }
}

data class LlmVendorCreateRequest(
    val name: String,
    val description: String? = null,
)

data class LlmVendorUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

data class LlmVendorResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

private fun LlmVendor.toResponse() = LlmVendorResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
