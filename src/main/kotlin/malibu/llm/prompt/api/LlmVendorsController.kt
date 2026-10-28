package malibu.llm.prompt.api

import malibu.llm.prompt.data.dto.LlmVendorCreateRequest
import malibu.llm.prompt.data.dto.LlmVendorResponse
import malibu.llm.prompt.data.dto.LlmVendorSearchSpec
import malibu.llm.prompt.data.dto.LlmVendorUpdateRequest
import malibu.llm.prompt.data.entity.LlmVendor
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
import java.util.UUID

@RestController
@RequestMapping("/llm-vendors")
class LlmVendorsController(
    private val llmCatalogService: LlmCatalogService,
) {

    @PreAuthorize("hasRole('PLATFORM_USER')")
    @GetMapping
    fun list(
        @ParameterObject searchSpec: LlmVendorSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmVendorResponse> =
        llmCatalogService.listVendors(searchSpec, pageable).toResponse { it.toResponse() }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: LlmVendorCreateRequest): LlmVendorResponse {
        val vendor = llmCatalogService.createVendor(request.name, request.description)
        return vendor.toResponse()
    }

    @PreAuthorize("hasRole('PLATFORM_USER')")
    @GetMapping("/{vendorId}")
    fun get(@PathVariable vendorId: UUID): LlmVendorResponse {
        return llmCatalogService.getVendor(vendorId).toResponse()
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PatchMapping("/{vendorId}")
    fun update(
        @PathVariable vendorId: UUID,
        @RequestBody request: LlmVendorUpdateRequest,
    ): LlmVendorResponse {
        return llmCatalogService.updateVendor(vendorId, request.name, request.description).toResponse()
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @DeleteMapping("/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable vendorId: UUID) {
        llmCatalogService.deleteVendor(vendorId)
    }
}

private fun LlmVendor.toResponse() = LlmVendorResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
