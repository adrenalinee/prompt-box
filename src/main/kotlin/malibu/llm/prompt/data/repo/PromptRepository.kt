package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Prompt
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PromptRepository : JpaRepository<Prompt, UUID> {
    fun findByWorkspaceId(workspaceId: UUID, pageable: Pageable): Page<Prompt>
    fun findByWorkspaceIdAndNameContainingIgnoreCase(
        workspaceId: UUID,
        name: String,
        pageable: Pageable,
    ): Page<Prompt>
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Prompt>
    fun countByWorkspaceId(workspaceId: UUID): Long
}
