package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
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
@Tag(name = "Workspace", description = "workspace 자체와 멤버, 기본 호출 옵션. 프롬프트 / API 키 / LLM 호출은 /workspaces/{workspaceId}/... 아래 각 API 에 있다. workspace 생성은 POST /organizations/{organizationId}/workspaces.")
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
    @Operation(summary = "내 workspace 목록", description = "직접 멤버인 workspace 와 org 역할로 접근할 수 있는 workspace.")
    fun list(
        @ParameterObject searchSpec: WorkspaceSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<WorkspaceResponse> =
        workspaceService.list(searchSpec, pageable, accessibleUserId = CurrentUser.userId())
            .toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    @Operation(summary = "workspace 조회", description = "includeDefaultOptions=true 면 기본 호출 옵션도 함께 내려준다.")
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
    @Operation(summary = "이 workspace 에서 내 권한", description = "콘솔에서 버튼 노출을 정할 때 쓴다. workspace 권한(permissions)과, workspace 삭제처럼 org 에서 판정하는 권한(organizationPermissions)을 함께 내려준다.")
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
    @Operation(summary = "workspace 수정")
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
    @Operation(summary = "workspace 삭제", description = "org 권한(workspace:delete)이 필요하다. 그래서 org 의 일반 멤버는 WORKSPACE_ADMIN 이어도 삭제할 수 없다. 프롬프트, API 키, 호출 로그가 남아 있으면 409.")
    fun delete(@PathVariable workspaceId: UUID) {
        workspaceService.delete(workspaceId)
    }

    @GetMapping("/{workspaceId}/models")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    @Operation(summary = "모델 목록 (workspace)", description = "LLM 모델 카탈로그를 workspace 멤버 권한으로 조회한다.")
    fun listModels(
        @PathVariable workspaceId: UUID,
        @ParameterObject searchSpec: LlmModelSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmModelResponse> =
        llmCatalogService.listModelsForWorkspace(workspaceId, searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/{workspaceId}/default-options")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_READ}')")
    @Operation(summary = "기본 호출 옵션 조회", description = "LLM 호출 때 요청의 overrideOptions 와 병합되는 workspace 기본값. 아직 저장한 적이 없으면 404.")
    fun getDefaultOptions(@PathVariable workspaceId: UUID): DefaultOptionsResponse {
        val options = workspaceService.getDefaultOptions(workspaceId)
        return options.toResponse()
    }

    @PutMapping("/{workspaceId}/default-options")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.CALL_OPTION_MANAGE}')")
    @Operation(summary = "기본 호출 옵션 저장", description = "전체를 교체한다(PUT). 보내지 않은 필드는 비워진다. 모델 타입에 맞지 않는 옵션은 호출 때 걸러진다.")
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
    @Operation(summary = "workspace 멤버 목록", description = "직접 할당된 멤버만. org 역할로 접근하는 ORG_OWNER / ORG_ADMIN 은 org 멤버 목록에서 본다.")
    fun listMembers(
        @PathVariable workspaceId: UUID,
        @PageableDefault(size = 50, sort = ["joinedAt"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): PageResponse<MemberResponse> =
        membershipService.listWorkspaceMembers(workspaceId, pageable).toResponse { it.toResponse() }

    @PostMapping("/{workspaceId}/members")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "workspace 멤버 추가", description = "그 org 의 멤버인 사용자를 email 로 추가한다. org 멤버가 아니면 400, 이미 멤버면 409.")
    fun addMember(
        @PathVariable workspaceId: UUID,
        @RequestBody request: MemberCreateRequest,
    ): MemberResponse =
        membershipService.addWorkspaceMember(workspaceId, request.email, request.role).toResponse()

    @PatchMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    @Operation(summary = "workspace 멤버 역할 변경")
    fun updateMember(
        @PathVariable workspaceId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MemberUpdateRequest,
    ): MemberResponse =
        membershipService.changeWorkspaceMemberRole(workspaceId, userId, request.role).toResponse()

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.WORKSPACE_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "workspace 멤버 내보내기")
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
