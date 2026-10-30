package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface DefaultLlmCallOptionsRepository
    : JpaRepository<DefaultLlmCallOptions, UUID>, QuerydslPredicateExecutor<DefaultLlmCallOptions> {
    fun findByWorkspaceId(workspaceId: UUID): DefaultLlmCallOptions?
}
