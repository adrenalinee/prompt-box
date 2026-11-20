package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.OrgAuthz
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.data.dto.MemberCreateRequest
import malibu.llm.prompt.data.dto.MemberResponse
import malibu.llm.prompt.data.dto.MemberUpdateRequest
import malibu.llm.prompt.data.dto.MyOrganizationResponse
import malibu.llm.prompt.data.dto.OrganizationPermissionsResponse
import malibu.llm.prompt.data.dto.OrganizationResponse
import malibu.llm.prompt.data.dto.OrganizationUpdateRequest
import malibu.llm.prompt.data.dto.OwnershipTransferRequest
import malibu.llm.prompt.data.dto.WorkspaceCreateRequest
import malibu.llm.prompt.data.dto.WorkspaceResponse
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Organization
import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.security.CurrentUser
import malibu.llm.prompt.service.MembershipService
import malibu.llm.prompt.service.OrganizationService
import malibu.llm.prompt.service.WorkspaceService
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
 * org 와 org 멤버, org 안의 workspace.
 * org 생성 / 전체 조회 / 정지는 플랫폼 관리(/admin/organizations).
 */
@Tag(name = "Organization", description = "org 와 org 멤버, org 안의 workspace. org 생성 / 전체 조회 / 정지는 플랫폼 관리 API(/admin/organizations)에 있다.")
@RestController
@RequestMapping("/organizations")
class OrganizationsController(
    private val organizationService: OrganizationService,
    private val membershipService: MembershipService,
    private val workspaceService: WorkspaceService,
    private val orgAuthz: OrgAuthz,
) {

    /** 내가 멤버인 org. SUSPENDED org 도 status 와 함께 내려간다(접근은 404). */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "내 Organization 목록", description = "내가 멤버인 org 와 내 역할. SUSPENDED org 도 status 와 함께 내려가지만, 접근하면 404 다.")
    fun listMine(): List<MyOrganizationResponse> =
        organizationService.listMemberships(CurrentUser.userId()).map {
            MyOrganizationResponse(
                id = requireNotNull(it.organization.id),
                name = it.organization.name,
                status = it.organization.status,
                role = it.role.code,
            )
        }

    @GetMapping("/{organizationId}")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_READ}')")
    @Operation(summary = "Organization 조회")
    fun get(@PathVariable organizationId: UUID): OrganizationResponse =
        organizationService.get(organizationId).toResponse()

    @GetMapping("/{organizationId}/my-permissions")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_READ}')")
    @Operation(summary = "이 org 에서 내 권한", description = "콘솔에서 버튼 노출을 정할 때 쓴다.")
    fun myPermissions(@PathVariable organizationId: UUID): OrganizationPermissionsResponse =
        OrganizationPermissionsResponse(
            organizationId = organizationId,
            permissions = orgAuthz.permissions(organizationId).sorted(),
        )

    @PatchMapping("/{organizationId}")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_UPDATE}')")
    @Operation(summary = "Organization 수정")
    fun update(
        @PathVariable organizationId: UUID,
        @RequestBody request: OrganizationUpdateRequest,
    ): OrganizationResponse =
        organizationService.update(organizationId, request.name).toResponse()

    /** workspace 가 남아 있으면 409. */
    @DeleteMapping("/{organizationId}")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_DELETE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Organization 삭제", description = "workspace 가 남아 있으면 409.")
    fun delete(@PathVariable organizationId: UUID) {
        organizationService.delete(organizationId)
    }

    // ---- 멤버 ----

    @GetMapping("/{organizationId}/members")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_READ}')")
    @Operation(summary = "org 멤버 목록")
    fun listMembers(
        @PathVariable organizationId: UUID,
        @PageableDefault(size = 50, sort = ["joinedAt"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): PageResponse<MemberResponse> =
        membershipService.listOrganizationMembers(organizationId, pageable).toResponse { it.toResponse() }

    /** 기존 사용자를 이메일로 추가한다. ORG_OWNER 는 부여할 수 없다(소유권 이전 사용). */
    @PostMapping("/{organizationId}/members")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "org 멤버 추가", description = "이미 등록된 사용자를 email 로 추가한다. ORG_OWNER 는 부여할 수 없다(소유권 이전 API 를 쓴다).")
    fun addMember(
        @PathVariable organizationId: UUID,
        @RequestBody request: MemberCreateRequest,
    ): MemberResponse =
        membershipService.addOrganizationMember(organizationId, request.email, request.role).toResponse()

    /** OWNER 부여/회수는 할 수 없다. */
    @PatchMapping("/{organizationId}/members/{userId}")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_MEMBER_MANAGE}')")
    @Operation(summary = "org 멤버 역할 변경", description = "ORG_OWNER 를 부여하거나 회수할 수 없다.")
    fun updateMember(
        @PathVariable organizationId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MemberUpdateRequest,
    ): MemberResponse =
        membershipService.changeOrganizationMemberRole(organizationId, userId, request.role).toResponse()

    /** OWNER 는 내보낼 수 없다. org 의 workspace 멤버십도 함께 지워진다. */
    @DeleteMapping("/{organizationId}/members/{userId}")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_MEMBER_MANAGE}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "org 멤버 내보내기", description = "ORG_OWNER 는 내보낼 수 없다. 그 사용자의 이 org 안 workspace 멤버십도 함께 지워진다.")
    fun removeMember(
        @PathVariable organizationId: UUID,
        @PathVariable userId: UUID,
    ) {
        membershipService.removeOrganizationMember(organizationId, userId)
    }

    /** 대상 멤버가 ORG_OWNER 가 되고 호출자는 ORG_ADMIN 이 된다. */
    @PostMapping("/{organizationId}/ownership-transfer")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_OWNERSHIP_TRANSFER}')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "소유권 이전", description = "대상(이미 org 멤버여야 한다)이 ORG_OWNER 가 되고, 호출자는 ORG_ADMIN 이 된다.")
    fun transferOwnership(
        @PathVariable organizationId: UUID,
        @RequestBody request: OwnershipTransferRequest,
    ) {
        membershipService.transferOwnership(organizationId, CurrentUser.userId(), request.userId)
    }

    // ---- workspace ----

    /** workspace:list_all 이 있으면 org 의 전체 workspace, 없으면 내가 접근 가능한 것만. */
    @GetMapping("/{organizationId}/workspaces")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_READ}')")
    @Operation(summary = "org 의 workspace 목록", description = "workspace:list_all 권한이 있으면 org 의 모든 workspace, 없으면 내가 접근할 수 있는 것만 내려간다.")
    fun listWorkspaces(
        @PathVariable organizationId: UUID,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<WorkspaceResponse> {
        val listAll = Permissions.WORKSPACE_LIST_ALL in orgAuthz.permissions(organizationId)
        return workspaceService.list(
            WorkspaceSearchSpec(name = name, organizationId = organizationId),
            pageable,
            accessibleUserId = if (listAll) null else CurrentUser.userId(),
        ).toResponse { it.toResponse() }
    }

    /** 만든 사용자는 그 workspace 의 WORKSPACE_ADMIN 이 된다. */
    @PostMapping("/{organizationId}/workspaces")
    @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.WORKSPACE_CREATE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "workspace 생성", description = "만든 사용자는 그 workspace 의 WORKSPACE_ADMIN 이 된다.")
    fun createWorkspace(
        @PathVariable organizationId: UUID,
        @RequestBody request: WorkspaceCreateRequest,
    ): WorkspaceResponse =
        workspaceService.create(
            organizationId = organizationId,
            name = request.name,
            description = request.description,
            defaultModelId = request.defaultModelId,
            creatorUserId = CurrentUser.userId(),
        ).toResponse()
}

fun Organization.toResponse() = OrganizationResponse(
    id = requireNotNull(id),
    name = name,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun OrganizationMember.toResponse() = MemberResponse(
    userId = requireNotNull(user.id),
    email = user.email,
    name = user.name,
    role = role.code,
    joinedAt = joinedAt,
)

fun WorkspaceMember.toResponse() = MemberResponse(
    userId = requireNotNull(user.id),
    email = user.email,
    name = user.name,
    role = role.code,
    joinedAt = joinedAt,
)
