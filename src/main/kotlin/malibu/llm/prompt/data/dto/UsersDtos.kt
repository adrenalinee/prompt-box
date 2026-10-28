package malibu.llm.prompt.data.dto

import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.data.WorkspaceRole
import java.time.Instant
import java.util.UUID

data class AppUserResponse(
    val id: UUID,
    val email: String?,
    val name: String?,
    val platformRole: PlatformRole,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class MeResponse(
    val user: AppUserResponse,
    val memberships: List<MyMembershipResponse>,
)

data class MyMembershipResponse(
    val workspaceId: UUID,
    val workspaceName: String,
    val role: WorkspaceRole,
)

data class PlatformRoleUpdateRequest(
    val platformRole: PlatformRole,
)

data class WorkspaceMemberCreateRequest(
    /** userId 와 email 중 하나로 사용자를 지정한다. 둘 다 있으면 userId 를 쓴다. */
    val userId: UUID? = null,
    val email: String? = null,
    val role: WorkspaceRole,
)

data class WorkspaceMemberUpdateRequest(
    val role: WorkspaceRole,
)

data class WorkspaceMemberResponse(
    val workspaceId: UUID,
    val userId: UUID,
    val email: String?,
    val name: String?,
    val role: WorkspaceRole,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
