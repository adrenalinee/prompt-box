package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.AdditionalInputItem
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor

interface AdditionalInputItemRepository
    : JpaRepository<AdditionalInputItem, Long>, QuerydslPredicateExecutor<AdditionalInputItem> {
    fun findByPromptRefIdOrderByPositionAsc(promptRefId: Long): List<AdditionalInputItem>
    fun findByPromptRefId(promptRefId: Long, pageable: Pageable): Page<AdditionalInputItem>
    fun countByPromptRefId(promptRefId: Long): Long
    fun deleteByPromptRefId(promptRefId: Long)
}
