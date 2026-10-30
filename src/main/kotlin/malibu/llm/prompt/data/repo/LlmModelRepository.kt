package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface LlmModelRepository
    : JpaRepository<LlmModel, Long>, QuerydslPredicateExecutor<LlmModel> {
    fun countByLlmVendorId(llmVendorId: UUID): Long
}
