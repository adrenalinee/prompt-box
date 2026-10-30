package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.InputItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface InputItemRepository
    : JpaRepository<InputItem, Long>, QuerydslPredicateExecutor<InputItem> {
    fun findByLlmCallLogIdOrderByPositionAsc(llmCallLogId: UUID): List<InputItem>
}
