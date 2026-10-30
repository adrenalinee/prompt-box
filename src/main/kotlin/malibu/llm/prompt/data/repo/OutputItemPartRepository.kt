package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.OutputItemPart
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor

interface OutputItemPartRepository
    : JpaRepository<OutputItemPart, Long>, QuerydslPredicateExecutor<OutputItemPart> {
    fun findByOutputItemIdInOrderByPartIndexAsc(outputItemIds: List<Long>): List<OutputItemPart>
}
