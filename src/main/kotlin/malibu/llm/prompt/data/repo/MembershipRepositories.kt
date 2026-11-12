package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.OrganizationMemberId
import malibu.llm.prompt.data.entity.PlatformRoleAssignment
import malibu.llm.prompt.data.entity.PlatformRoleAssignmentId
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.entity.WorkspaceMemberId
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PlatformRoleAssignmentRepository : JpaRepository<PlatformRoleAssignment, PlatformRoleAssignmentId> {
    @EntityGraph(attributePaths = ["role"])
    fun findByIdUserId(userId: UUID): List<PlatformRoleAssignment>
}

interface OrganizationMemberRepository : JpaRepository<OrganizationMember, OrganizationMemberId> {
    @EntityGraph(attributePaths = ["organization", "role"])
    fun findByIdUserIdOrderByJoinedAtAsc(userId: UUID): List<OrganizationMember>

    @EntityGraph(attributePaths = ["user", "role"])
    fun findByIdOrganizationId(organizationId: UUID, pageable: Pageable): Page<OrganizationMember>

    fun countByIdOrganizationIdAndRoleCode(organizationId: UUID, roleCode: String): Long

    @Modifying
    @Query("delete from OrganizationMember om where om.id.organizationId = :organizationId")
    fun deleteByOrganizationId(organizationId: UUID): Int
}

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, WorkspaceMemberId> {
    fun findFirstByIdUserIdOrderByJoinedAtAsc(userId: UUID): WorkspaceMember?

    @EntityGraph(attributePaths = ["user", "role"])
    fun findByIdWorkspaceId(workspaceId: UUID, pageable: Pageable): Page<WorkspaceMember>

    @Modifying
    @Query("delete from WorkspaceMember wm where wm.id.workspaceId = :workspaceId")
    fun deleteByWorkspaceId(workspaceId: UUID): Int

    /** org 탈퇴 시. PostgreSQL 은 복합 FK cascade 로도 지워지지만 H2(ddl-auto) 스키마에는 그 FK 가 없다. */
    @Modifying
    @Query("delete from WorkspaceMember wm where wm.organizationId = :organizationId and wm.id.userId = :userId")
    fun deleteByOrganizationIdAndUserId(organizationId: UUID, userId: UUID): Int
}
