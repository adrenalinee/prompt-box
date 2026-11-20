package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
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
@Tag(name = "Prompt", description = "프롬프트와 ref(버전), ref 의 추가 입력 항목. 프롬프트는 git 처럼 ref 로 버전을 관리한다. BRANCH 는 수정할 수 있고 TAG 는 고정된 스냅샷이다. TAG 생성 / 삭제와 latest tag 변경은 publish 권한(prompt_ref:publish)이 필요하다.")
@RestController
@RequestMapping("/workspaces/{workspaceId}/prompts")
class PromptsController(
    private val promptService: PromptService,
    private val wsAuthz: WorkspaceAuthz,
) {

    @PostMapping
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "프롬프트 생성", description = "instructions 와 추가 입력으로 첫 BRANCH(mainRefName, 기본값 main)를 만들고 default branch 로 지정한다.")
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
    @Operation(summary = "프롬프트 목록", description = "name 으로 검색한다.")
    fun list(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptResponse> =
        promptService.listPrompts(PromptSearchSpec(workspaceId = workspaceId, name = name), pageable)
            .toResponse { it.toResponse() }

    @GetMapping("/{promptId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
    @Operation(summary = "프롬프트 조회")
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
    @Operation(summary = "프롬프트 수정", description = "defaultBranchRefId 는 BRANCH, latestTagRefId 는 TAG 여야 한다.")
    @PermissionDoc("workspace 권한 `${Permissions.PROMPT_WRITE}`. latestTagRefId 를 보내면(null 로 해제 포함) `${Permissions.PROMPT_REF_PUBLISH}` 도 필요하다.")
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
    @Operation(summary = "프롬프트 삭제", description = "ref 중 하나라도 호출 로그가 있으면 409.")
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
    @Operation(summary = "ref 생성", description = "fromRefId 의 instructions 와 추가 입력을 복사해 BRANCH 또는 TAG 를 만든다. BRANCH 는 fromRefId 를 생략하면 빈 instructions 로 만들어지고, TAG 는 fromRefId 가 필요하다. TAG 를 만들면 latest tag 가 된다. 프롬프트당 ref 는 100개까지.")
    @PermissionDoc("workspace 권한. BRANCH 생성은 `${Permissions.PROMPT_WRITE}`, TAG 생성은 `${Permissions.PROMPT_REF_PUBLISH}`.")
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
    @Operation(summary = "ref 목록", description = "type(BRANCH / TAG), name 으로 거른다.")
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
    @Operation(summary = "ref 조회")
    fun getRef(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
    ): PromptRefResponse {
        return promptService.getRef(workspaceId, promptId, refId).toResponse()
    }

    @PatchMapping("/{promptId}/refs/{refId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @Operation(summary = "ref 수정", description = "BRANCH 만 수정할 수 있다. TAG 면 400.")
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
    @Operation(summary = "ref 삭제", description = "default branch, latest tag, 호출 로그가 있는 ref 는 지울 수 없다(409).")
    @PermissionDoc("workspace 권한 `${Permissions.PROMPT_WRITE}`. TAG 를 지우려면 `${Permissions.PROMPT_REF_PUBLISH}` 도 필요하다.")
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
    @Operation(summary = "추가 입력 항목 추가", description = "LLM 호출 때 instructions 뒤에 붙는 대화 입력(role + message). position(1부터)을 주면 그 자리에 끼워 넣고, 생략하면 맨 뒤에 붙인다. ref 당 100개까지.")
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
    @Operation(summary = "추가 입력 항목 목록")
    fun listAdditionalInputs(
        @PathVariable workspaceId: UUID,
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PageableDefault(size = 20, sort = ["position"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<AdditionalInputItemResponse> =
        promptService.listAdditionalInputs(workspaceId, promptId, refId, pageable).toResponse { it.toResponse() }

    @PatchMapping("/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")
    @Operation(summary = "추가 입력 항목 수정", description = "position 을 바꾸면 나머지 항목의 순서가 다시 매겨진다.")
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
    @Operation(summary = "추가 입력 항목 삭제", description = "나머지 항목의 순서가 다시 매겨진다.")
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
