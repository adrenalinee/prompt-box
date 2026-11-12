package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.OrganizationMemberId
import malibu.llm.prompt.data.entity.PlatformRoleAssignment
import malibu.llm.prompt.data.entity.PlatformRoleAssignmentId
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.entity.WorkspaceMemberId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlatformRoleAssignmentRepository : JpaRepository<PlatformRoleAssignment, PlatformRoleAssignmentId>

interface OrganizationMemberRepository : JpaRepository<OrganizationMember, OrganizationMemberId>

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, WorkspaceMemberId> {
    fun findFirstByIdUserIdOrderByJoinedAtAsc(userId: UUID): WorkspaceMember?
}
