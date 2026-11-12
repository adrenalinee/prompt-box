package malibu.llm.prompt.api

import malibu.llm.prompt.authz.PermissionResolver
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.authz.WorkspaceAuthz
import malibu.llm.prompt.data.dto.DefaultOptionsRequest
import malibu.llm.prompt.data.dto.DefaultOptionsResponse
import malibu.llm.prompt.data.dto.LlmModelResponse
import malibu.llm.prompt.data.dto.LlmModelSearchSpec
import malibu.llm.prompt.data.dto.MemberCreateRequest
import malibu.llm.prompt.data.dto.MemberResponse
import malibu.llm.prompt.data.dto.MemberUpdateRequest
import malibu.llm.prompt.data.dto.WorkspaceDetailResponse
import malibu.llm.prompt.data.dto.WorkspacePermissionsResponse
import malibu.llm.prompt.data.dto.WorkspaceResponse
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.dto.WorkspaceUpdateRequest
import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.security.CurrentUser
import malibu.llm.prompt.service.LlmCatalogService
import malibu.llm.prompt.service.MembershipService
import malibu.llm.prompt.service.WorkspaceService
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * workspace 자체와 멤버, 기본 옵션.
 * 하위 리소스: PromptsController, LlmApiKeysController, LlmCallsController (모두 /workspaces/{workspaceId}/...)
 * 생성: POST /organizations/{organizationId}/workspaces (OrganizationsController)
 */
@RestController
@RequestMapping("/workspaces")
class WorkspacesController(
    private val workspaceService: WorkspaceService,
    private val llmCatalogService: LlmCatalogService,
    private val membershipService: MembershipService,
    private val wsAuthz: WorkspaceAuthz,
    private val permissionResolver: PermissionResolver,
) {

    /** 내가 접근 가능한 workspace (직접 할당 ∪ org 역할 함의). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @ParameterObject searchSpec: WorkspaceSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<WorkspaceResponse> =
        workspaceService.list(searchSpec, pageable, accessibleUserId = CurrentUser.userId())
            .toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    fun get(
        @PathVariable workspaceId: UUID,
        @RequestParam(required = false, defaultValue = "false") includeDefaultOptions: Boolean,
    ): WorkspaceDetailResponse {
        val workspace = workspaceService.get(workspaceId)
        val defaultOptions = if (includeDefaultOptions) {
            workspaceService.findDefaultOptions(workspaceId)?.toResponse()
        } else {
            null
        }
        return workspace.toDetailResponse(defaultOptions)
    }

    /** 콘솔 버튼 노출용. workspace:delete 등 org 권한은 organizationPermissions 에 있다. */
    @GetMapping("/{workspaceId}/my-permissions")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    fun myPermissions(@PathVariable workspaceId: UUID): WorkspacePermissionsResponse {
        val workspace = workspaceService.get(workspaceId)
        return WorkspacePermissionsResponse(
            workspaceId = workspaceId,
            organizationId = requireNotNull(workspace.organization.id),
            permissions = wsAuthz.permissions(workspaceId).sorted(),
            organizationPermissions = permissionResolver
                .organizationByWorkspace(workspaceId, CurrentUser.userId())
                .sorted(),
        )
    }

    @PatchMapping("/{workspaceId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_UPDATE}')")
    fun update(
        @PathVariable workspaceId: UUID,
        @RequestBody request: WorkspaceUpdateRequest,
    ): WorkspaceResponse {
        val workspace = workspaceService.update(workspaceId, request.name, request.description, request.defaultModelId)
        return workspace.toResponse()
    }

    /** workspace 삭제는 org 권한이다. ORG_MEMBER + WORKSPACE_ADMIN 은 삭제할 수 없다. */
    @DeleteMapping("/{workspaceId}")
    @PreAuthorize("@orgAuthz.hasForWorkspace(#workspaceId, '${Permissions.WORKSPACE_DELETE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workspaceId: UUID) {
        workspaceService.delete(workspaceId)
    }

    @GetMapping("/{workspaceId}/models")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    fun listModels(
        @PathVariable workspaceId: UUID,
        @ParameterObject searchSpec: LlmModelSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmModelResponse> =
        llmCatalogService.listModelsForWorkspace(workspaceId, searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}/default-options")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    fun getDefaultOptions(@PathVariable workspaceId: UUID): DefaultOptionsResponse {
        val options = workspaceService.getDefaultOptions(workspaceId)
        return options.toResponse()
    }

    @PutMapping("/{workspaceId}/default-options")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.CALL_OPTION_MANAGE}')")
    fun upsertDefaultOptions(
        @PathVariable workspaceId: UUID,
        @RequestBody request: DefaultOptionsRequest,
    ): DefaultOptionsResponse {
        val options = workspaceService.upsertDefaultOptions(
            workspaceId = workspaceId,
            temperature = request.temperature,
            topP = request.topP,
            topK = request.topK,
            includeThoughts = request.includeThoughts,
            maxTokens = request.maxTokens,
            textFormat = request.textFormat,
            effort = request.effort,
            verbosity = request.verbosity,
            summary = request.summary,
        )
        return options.toResponse()
    }

    // ---- 멤버 ----

    /** 직접 할당된 멤버만. org 역할로 함의된 ORG_OWNER/ORG_ADMIN 은 org 멤버 목록에서 본다. */
    @GetMapping("/{workspaceId}/members")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    fun listMembers(
        @PathVariable workspaceId: UUID,
        @PageableDefault(size = 50, sort = ["joinedAt"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): PageResponse<MemberResponse> =
        membershipService.listWorkspaceMembers(workspaceId, pageable).toResponse { it.toResponse() }

    @PostMapping("/{workspaceId}/members")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @PathVariable workspaceId: UUID,
        @RequestBody request: MemberCreateRequest,
    ): MemberResponse =
        membershipService.addWorkspaceMember(workspaceId, request.email, request.role).toResponse()

    @PatchMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    fun updateMember(
        @PathVariable workspaceId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MemberUpdateRequest,
    ): MemberResponse =
        membershipService.changeWorkspaceMemberRole(workspaceId, userId, request.role).toResponse()

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(
        @PathVariable workspaceId: UUID,
        @PathVariable userId: UUID,
    ) {
        membershipService.removeWorkspaceMember(workspaceId, userId)
    }
}

fun Workspace.toResponse() = WorkspaceResponse(
    id = requireNotNull(id),
    organizationId = requireNotNull(organization.id),
    name = name,
    description = description,
    defaultModelId = defaultModel?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Workspace.toDetailResponse(defaultOptions: DefaultOptionsResponse?) = WorkspaceDetailResponse(
    id = requireNotNull(id),
    organizationId = requireNotNull(organization.id),
    name = name,
    description = description,
    defaultModelId = defaultModel?.id,
    defaultLlmCallOptions = defaultOptions,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun DefaultLlmCallOptions.toResponse() = DefaultOptionsResponse(
    workspaceId = requireNotNull(workspaceId),
    temperature = temperature,
    topP = topP,
    topK = topK,
    includeThoughts = includeThoughts,
    maxTokens = maxTokens,
    textFormat = textFormat,
    effort = effort,
    verbosity = verbosity,
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
