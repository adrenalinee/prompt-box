package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.WorkspaceRole
import malibu.llm.prompt.data.entity.WorkspaceMember
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, UUID> {
    @EntityGraph(attributePaths = ["user"])
    fun findByWorkspaceIdAndUserId(workspaceId: UUID, userId: UUID): WorkspaceMember?

    @EntityGraph(attributePaths = ["user"])
    fun findByWorkspaceIdOrderByCreatedAtAsc(workspaceId: UUID): List<WorkspaceMember>

    @EntityGraph(attributePaths = ["workspace"])
    fun findByUserIdOrderByCreatedAtAsc(userId: UUID): List<WorkspaceMember>

    fun countByWorkspaceIdAndRole(workspaceId: UUID, role: WorkspaceRole): Long

    @Transactional
    fun deleteByWorkspaceId(workspaceId: UUID)
}
