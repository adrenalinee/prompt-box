package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.PromptRef
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PromptRefRepository : JpaRepository<PromptRef, Long>, PromptRefRepositoryCustom {
    fun findByPromptId(promptId: UUID): List<PromptRef>
    fun countByPromptId(promptId: UUID): Long
}
