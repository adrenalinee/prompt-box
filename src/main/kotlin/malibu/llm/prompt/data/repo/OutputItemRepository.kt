package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.OutputItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface OutputItemRepository
    : JpaRepository<OutputItem, Long>, QuerydslPredicateExecutor<OutputItem> {
    fun findByLlmCallLogIdOrderByPositionAsc(llmCallLogId: UUID): List<OutputItem>
}
