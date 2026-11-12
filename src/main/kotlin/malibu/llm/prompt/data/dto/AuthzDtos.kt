package malibu.llm.prompt.data.dto

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
    val name: String? = null,
    val status: UserStatus? = null,
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
