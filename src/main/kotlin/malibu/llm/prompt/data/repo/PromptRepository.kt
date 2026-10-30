package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Prompt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface PromptRepository
    : JpaRepository<Prompt, UUID>, QuerydslPredicateExecutor<Prompt>, PromptRepositoryCustom {
    fun countByWorkspaceId(workspaceId: UUID): Long
}
