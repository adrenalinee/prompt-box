package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Prompt
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PromptRepository : JpaRepository<Prompt, UUID>, PromptRepositoryCustom {
    fun countByWorkspaceId(workspaceId: UUID): Long

    /** IDOR 방어: workspace 하위 리소스는 workspace 조건으로만 조회한다. (authz-spec §5) */
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): Prompt?
}
