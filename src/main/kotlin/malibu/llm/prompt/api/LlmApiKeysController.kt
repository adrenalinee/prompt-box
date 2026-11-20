package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.data.dto.LlmApiKeyCreateRequest
import malibu.llm.prompt.data.dto.LlmApiKeyResponse
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.dto.LlmApiKeyUpdateRequest
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.service.LlmCatalogService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 목록/조회는 실행하려면 키를 골라야 하므로 llm:execute 로 연다. 값은 last5 만 내려간다.
 * apiKeyId 는 경로의 workspaceId 조건으로만 조회한다. (authz-spec §5)
 */
@Tag(name = "LLM API 키", description = "workspace 에 등록한 벤더 API 키. 값은 암호화해 저장하며 응답에는 끝 5자리(last5)만 내려간다. LLM 호출 때 키를 골라야 하므로 목록 / 조회는 llm:execute 권한으로 열려 있고, 변경은 api_key:manage 가 필요하다.")
@RestController
@RequestMapping("/workspaces/{workspaceId}/llm-api-keys")
class LlmApiKeysController(
    private val llmCatalogService: LlmCatalogService,
) {

    @PostMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.API_KEY_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "API 키 등록")
    fun create(
        @PathVariable workspaceId: UUID,
        @RequestBody request: LlmApiKeyCreateRequest,
    ): LlmApiKeyResponse {
        val apiKey = llmCatalogService.createApiKey(
            workspaceId = workspaceId,
            vendorId = request.vendorId,
            name = request.name,
            value = request.value,
            description = request.description,
        )
        return apiKey.toResponse()
    }

    @GetMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.LLM_EXECUTE}')")
    @Operation(summary = "API 키 목록", description = "vendorId, name 으로 거른다.")
    fun list(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) vendorId: UUID?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmApiKeyResponse> =
        llmCatalogService.listApiKeys(
            LlmApiKeySearchSpec(workspaceId = workspaceId, vendorId = vendorId, name = name),
            pageable,
        ).toResponse { it.toResponse() }

    @GetMapping("/{apiKeyId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.LLM_EXECUTE}')")
    @Operation(summary = "API 키 조회")
    fun get(
        @PathVariable workspaceId: UUID,
        @PathVariable apiKeyId: UUID,
    ): LlmApiKeyResponse {
        return llmCatalogService.getApiKey(workspaceId, apiKeyId).toResponse()
    }

    @PatchMapping("/{apiKeyId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.API_KEY_MANAGE}')")
    @Operation(summary = "API 키 수정", description = "value 를 보내면 키 값을 교체한다.")
    fun update(
        @PathVariable workspaceId: UUID,
        @PathVariable apiKeyId: UUID,
        @RequestBody request: LlmApiKeyUpdateRequest,
    ): LlmApiKeyResponse {
        return llmCatalogService.updateApiKey(
            workspaceId = workspaceId,
            apiKeyId = apiKeyId,
            vendorId = request.vendorId,
            name = request.name,
            value = request.value,
            description = request.description,
        ).toResponse()
    }

    @DeleteMapping("/{apiKeyId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.API_KEY_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "API 키 삭제")
    fun delete(
        @PathVariable workspaceId: UUID,
        @PathVariable apiKeyId: UUID,
    ) {
        llmCatalogService.deleteApiKey(workspaceId, apiKeyId)
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
