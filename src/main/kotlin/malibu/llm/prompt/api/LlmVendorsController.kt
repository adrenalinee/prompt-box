package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.Permissions
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

@Tag(name = "LLM 벤더", description = "LLM 제공 벤더(openai / google / xai). 플랫폼 전체가 공유한다. 이름이 서버의 벤더 클라이언트 이름과 같아야 호출에 쓸 수 있다. 조회는 로그인한 사용자 누구나, 변경은 플랫폼 권한이 필요하다.")
@RestController
@RequestMapping("/llm-vendors")
class LlmVendorsController(
    private val llmCatalogService: LlmCatalogService,
) {

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "벤더 목록")
    fun list(
        @ParameterObject searchSpec: LlmVendorSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmVendorResponse> =
        llmCatalogService.listVendors(searchSpec, pageable).toResponse { it.toResponse() }

    @PostMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_VENDOR_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "벤더 생성")
    fun create(@RequestBody request: LlmVendorCreateRequest): LlmVendorResponse {
        val vendor = llmCatalogService.createVendor(request.name, request.description)
        return vendor.toResponse()
    }

    @GetMapping("/{vendorId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "벤더 조회")
    fun get(@PathVariable vendorId: UUID): LlmVendorResponse {
        return llmCatalogService.getVendor(vendorId).toResponse()
    }

    @PatchMapping("/{vendorId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_VENDOR_MANAGE}')")
    @Operation(summary = "벤더 수정")
    fun update(
        @PathVariable vendorId: UUID,
        @RequestBody request: LlmVendorUpdateRequest,
    ): LlmVendorResponse {
        return llmCatalogService.updateVendor(vendorId, request.name, request.description).toResponse()
    }

    @DeleteMapping("/{vendorId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.LLM_VENDOR_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "벤더 삭제", description = "이 벤더를 쓰는 모델이나 API 키가 있으면 409.")
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
