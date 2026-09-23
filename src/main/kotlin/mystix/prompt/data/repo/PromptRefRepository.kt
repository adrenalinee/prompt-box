package mystix.prompt.data.repo

import mystix.prompt.data.PromptRefType
import mystix.prompt.data.entity.PromptRef
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PromptRefRepository : JpaRepository<PromptRef, Long> {
    fun findByPromptId(promptId: UUID): List<PromptRef>
    fun countByPromptId(promptId: UUID): Long
    fun findByPromptId(promptId: UUID, pageable: Pageable): Page<PromptRef>
    fun findByPromptIdAndType(promptId: UUID, type: PromptRefType, pageable: Pageable): Page<PromptRef>
    fun findByPromptIdAndNameContainingIgnoreCase(promptId: UUID, name: String, pageable: Pageable): Page<PromptRef>
    fun findByPromptIdAndTypeAndNameContainingIgnoreCase(
        promptId: UUID,
        type: PromptRefType,
        name: String,
        pageable: Pageable,
    ): Page<PromptRef>
}
