package malibu.llm.prompt.api

import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.authz.WorkspaceAuthz
import malibu.llm.prompt.data.PromptRefType
import malibu.llm.prompt.data.dto.AdditionalInputCreateRequest
import malibu.llm.prompt.data.dto.AdditionalInputItemResponse
import malibu.llm.prompt.data.dto.AdditionalInputRequest
import malibu.llm.prompt.data.dto.AdditionalInputUpdateRequest
import malibu.llm.prompt.data.dto.PromptCreateRequest
import malibu.llm.prompt.data.dto.PromptRefCreateRequest
import malibu.llm.prompt.data.dto.PromptRefResponse
import malibu.llm.prompt.data.dto.PromptRefUpdateRequest
import malibu.llm.prompt.data.dto.PromptResponse
import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.dto.PromptUpdateRequest
import malibu.llm.prompt.data.entity.AdditionalInputItem
import malibu.llm.prompt.data.entity.Prompt
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.service.AdditionalInputItemSpec
import malibu.llm.prompt.service.PromptService
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
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
 * prompt / ref / additional input 은 경로의 workspaceId 조건으로만 조회한다. 다른 workspace 의 ID 는 404. (authz-spec §5)
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/prompts")
class PromptsController(
    private val promptService: PromptService,
    private val wsAuthz: WorkspaceAuthz,
) {

    @PostMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable workspaceId: UUID,
        @RequestBody request: PromptCreateRequest,
    ): PromptResponse {
        val prompt = promptService.createPrompt(
            workspaceId = workspaceId,
            name = request.name,
            description = request.description,
            instructions = request.instructions,
            additionalInputs = request.additionalInputs.map { it.toSpec() },
            mainRefName = request.mainRefName,
        )
        return prompt.toResponse()
    }

    @GetMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    fun list(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptResponse> =
        promptService.listPrompts(PromptSearchSpec(workspaceId = workspaceId, name = name), pageable)
            .toResponse { it.toResponse() }

    @GetMapping("/{promptId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    fun get(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
    ): PromptResponse {
        return promptService.getPrompt(workspaceId, promptId).toResponse()
    }

    /** latestTag 를 바꾸거나 해제(null)하는 것은 publish 다. */
    @PatchMapping("/{promptId}")
    @PreAuthorize(
        "@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')" +
            " and (!#request.latestTagRefId.isPresent() or @wsAuthz.has(#workspaceId, '${Permissions.PROMPT_REF_PUBLISH}'))"
    )
    fun update(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @RequestBody request: PromptUpdateRequest,
    ): PromptResponse {
        return promptService.updatePrompt(
            workspaceId,
            promptId,
            request.name,
            request.description,
            request.defaultBranchRefId,
            request.latestTagRefId,
        ).toResponse()
    }

    @DeleteMapping("/{promptId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_DELETE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
    ) {
        promptService.deletePrompt(workspaceId, promptId)
    }

    /** BRANCH 는 prompt:write, TAG 는 prompt_ref:publish. */
    @PostMapping("/{promptId}/refs")
    @PreAuthorize(
        "@wsAuthz.has(#workspaceId, #request.type == T(malibu.llm.prompt.data.PromptRefType).TAG" +
            " ? '${Permissions.PROMPT_REF_PUBLISH}' : '${Permissions.PROMPT_WRITE}')"
    )
    @ResponseStatus(HttpStatus.CREATED)
    fun createRef(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @RequestBody request: PromptRefCreateRequest,
    ): PromptRefResponse {
        return promptService.createRef(
            workspaceId = workspaceId,
            promptId = promptId,
            name = request.name,
            type = request.type,
            fromRefId = request.fromRefId,
            description = request.description,
        ).toResponse()
    }

    @GetMapping("/{promptId}/refs")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    fun listRefs(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @RequestParam(required = false) type: PromptRefType?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptRefResponse> =
        promptService.listRefs(workspaceId, promptId, type, name, pageable)
            .toResponse { it.toResponse() }

    @GetMapping("/{promptId}/refs/{refId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    fun getRef(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
    ): PromptRefResponse {
        return promptService.getRef(workspaceId, promptId, refId).toResponse()
    }

    @PatchMapping("/{promptId}/refs/{refId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    fun updateRef(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @RequestBody request: PromptRefUpdateRequest,
    ): PromptRefResponse {
        return promptService.updateRef(
            workspaceId = workspaceId,
            promptId = promptId,
            refId = refId,
            name = request.name,
            description = request.description,
            instructions = request.instructions,
        ).toResponse()
    }

    /** BRANCH 는 prompt:write, TAG 는 prompt_ref:publish. 타입은 저장된 ref 로 판단하므로 메서드 안에서 한 번 더 검사한다. */
    @DeleteMapping("/{promptId}/refs/{refId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRef(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
    ) {
        val ref = promptService.getRef(workspaceId, promptId, refId)
        if (ref.type == PromptRefType.TAG && !wsAuthz.has(workspaceId, Permissions.PROMPT_REF_PUBLISH)) {
            throw AccessDeniedException("TAG 삭제에는 ${Permissions.PROMPT_REF_PUBLISH} 권한이 필요합니다.")
        }
        promptService.deleteRef(workspaceId, promptId, refId)
    }

    @PostMapping("/{promptId}/refs/{refId}/additional-input-items")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAdditionalInput(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @RequestBody request: AdditionalInputCreateRequest,
    ): AdditionalInputItemResponse {
        return promptService.addAdditionalInput(
            workspaceId = workspaceId,
            promptId = promptId,
            refId = refId,
            position = request.position,
            role = request.role,
            message = request.message,
        ).toResponse()
    }

    @GetMapping("/{promptId}/refs/{refId}/additional-input-items")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    fun listAdditionalInputs(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PageableDefault(size = 20, sort = ["position"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<AdditionalInputItemResponse> =
        promptService.listAdditionalInputs(workspaceId, promptId, refId, pageable).toResponse { it.toResponse() }

    @PatchMapping("/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    fun updateAdditionalInput(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PathVariable additionalInputItemId: Long,
        @RequestBody request: AdditionalInputUpdateRequest,
    ): AdditionalInputItemResponse {
        return promptService.updateAdditionalInput(
            workspaceId = workspaceId,
            promptId = promptId,
            refId = refId,
            additionalInputItemId = additionalInputItemId,
            position = request.position,
            role = request.role,
            message = request.message,
        ).toResponse()
    }

    @DeleteMapping("/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAdditionalInput(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PathVariable additionalInputItemId: Long,
    ) {
        promptService.deleteAdditionalInput(workspaceId, promptId, refId, additionalInputItemId)
    }
}

fun Prompt.toResponse() = PromptResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    workspaceId = requireNotNull(workspace.id),
    defaultBranchRefId = defaultBranch?.id,
    latestTagRefId = latestTag?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun PromptRef.toResponse() = PromptRefResponse(
    id = id,
    promptId = requireNotNull(prompt.id),
    name = name,
    type = type,
    instructions = instructions,
    description = description,
    sourceTagRefId = sourceTagRef?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AdditionalInputItem.toResponse() = AdditionalInputItemResponse(
    id = id,
    position = position,
    role = role,
    message = message,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AdditionalInputRequest.toSpec() = AdditionalInputItemSpec(
    position = position,
    role = role,
    message = message,
)
