package malibu.llm.prompt.data.dto

import io.swagger.v3.oas.annotations.media.Schema
import malibu.llm.prompt.authz.AuthzScope
import malibu.llm.prompt.data.OrganizationStatus
import malibu.llm.prompt.data.UserStatus
import java.time.Instant
import java.util.UUID

// ---- 내 정보 ----

/** GET /me */
data class MeResponse(
    val id: UUID,
    val email: String,
    val name: String,
    val status: UserStatus,
    /** IdP 계정이 연결되었는지. local 프로파일 사용자는 false. */
    val linked: Boolean,
    val createdAt: Instant?,
    val platformRoles: List<String>,
    val platformPermissions: List<String>,
    val organizations: List<MyOrganizationResponse>,
)

data class MyOrganizationResponse(
    val id: UUID,
    val name: String,
    val status: OrganizationStatus,
    val role: String,
)

/** GET /organizations/{organizationId}/my-permissions */
data class OrganizationPermissionsResponse(
    val organizationId: UUID,
    val permissions: List<String>,
)

/**
 * GET /workspaces/{workspaceId}/my-permissions
 * workspace:delete 처럼 workspace 를 대상으로 하지만 org 권한인 것은 organizationPermissions 에 있다.
 */
data class WorkspacePermissionsResponse(
    val workspaceId: UUID,
    val organizationId: UUID,
    val permissions: List<String>,
    val organizationPermissions: List<String>,
)

// ---- 사용자 (플랫폼 관리) ----

data class UserResponse(
    val id: UUID,
    val email: String,
    val name: String,
    val status: UserStatus,
    /** IdP 계정이 연결되었는지(첫 로그인 완료). */
    val linked: Boolean,
    val platformRoles: List<String>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

/** POST /admin/users: 관리자 사전 등록. 개인 org + 기본 workspace 가 함께 만들어진다. */
data class UserCreateRequest(
    val email: String,
    val name: String,
)

/** PATCH /admin/users/{userId}. platformRoles 를 주면 그 목록으로 교체한다. */
data class UserUpdateRequest(
    @field:Schema(description = "이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
    @field:Schema(description = "상태. 보내지 않거나 null 이면 변경 없음. 자기 자신은 비활성화할 수 없다.")
    val status: UserStatus? = null,
    @field:Schema(description = "플랫폼 역할 코드 목록. 보내면 전체를 이 목록으로 교체한다(빈 배열이면 모두 회수). 보내지 않거나 null 이면 변경 없음.")
    val platformRoles: List<String>? = null,
)

// ---- Organization ----

data class OrganizationResponse(
    val id: UUID,
    val name: String,
    val status: OrganizationStatus,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

/** POST /admin/organizations. ownerUserId 를 생략하면 호출한 사용자가 ORG_OWNER 가 된다. */
data class OrganizationCreateRequest(
    val name: String,
    val ownerUserId: UUID? = null,
)

data class OrganizationUpdateRequest(
    @field:Schema(description = "이름. 보내지 않거나 null / 빈 문자열이면 변경 없음.")
    val name: String? = null,
)

data class OrganizationStatusRequest(
    val status: OrganizationStatus,
)

data class OwnershipTransferRequest(
    /** 새 ORG_OWNER. 이미 org 멤버여야 한다. 기존 OWNER(호출자)는 ORG_ADMIN 이 된다. */
    val userId: UUID,
)

// ---- 멤버 ----

data class MemberResponse(
    val userId: UUID,
    val email: String,
    val name: String,
    val role: String,
    val joinedAt: Instant?,
)

/** 기존 사용자를 이메일로 추가한다. */
data class MemberCreateRequest(
    val email: String,
    val role: String,
)

data class MemberUpdateRequest(
    val role: String,
)

// ---- 권한 카탈로그 / 권한 요약 ----

/** GET /authz/catalog: 시드된 권한과 역할. 수정 API 는 없다(마이그레이션으로만 바뀐다). */
data class AuthzCatalogResponse(
    /** scope(PLATFORM → ORGANIZATION → WORKSPACE), 시드 순서 */
    val permissions: List<PermissionCatalogItem>,
    /** scope(PLATFORM → ORGANIZATION → WORKSPACE), 시드 순서 */
    val roles: List<RoleCatalogItem>,
)

data class PermissionCatalogItem(
    val code: String,
    val scope: AuthzScope,
    val description: String,
    /** 이 권한을 직접 가진 역할 */
    val roles: List<String>,
)

data class RoleCatalogItem(
    val code: String,
    val scope: AuthzScope,
    val description: String,
    /** ORGANIZATION 역할만: org 의 모든 workspace 에서 이 workspace 역할로 판정한다. */
    val impliedWorkspaceRole: String?,
    val permissions: List<String>,
)

/**
 * GET /me/access, GET /admin/users/{userId}/access: 한 사용자의 역할과 권한 요약.
 *
 * org / workspace 권한은 역할로 받는 권한이다. org 가 ACTIVE 가 아니면 실제 판정에서는 모두 거부되므로
 * organizations[].status 로 효력 여부를 판단한다.
 */
data class AccessSummaryResponse(
    val userId: UUID,
    val platform: PlatformAccess,
    /** 가입순 */
    val organizations: List<OrganizationAccess>,
    /** 직접 멤버인 workspace ∪ org 역할의 함의로 접근하는 workspace. 조직 가입순, 이름순 */
    val workspaces: List<WorkspaceAccess>,
)

data class PlatformAccess(
    val roles: List<String>,
    val permissions: List<String>,
)

data class OrganizationAccess(
    val id: UUID,
    val name: String,
    val status: OrganizationStatus,
    val role: String,
    val impliedWorkspaceRole: String?,
    val permissions: List<String>,
)

data class WorkspaceAccess(
    val id: UUID,
    val name: String,
    val organizationId: UUID,
    /** workspace_member 로 직접 받은 역할 */
    val directRole: String?,
    /** org 역할의 함의로 받은 역할 */
    val impliedRole: String?,
    /** directRole ∪ impliedRole 의 권한 */
    val permissions: List<String>,
)
