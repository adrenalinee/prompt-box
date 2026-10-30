package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmApiKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.*

interface LlmApiKeyRepository
    : JpaRepository<LlmApiKey, UUID>, QuerydslPredicateExecutor<LlmApiKey>, LlmApiKeyRepositoryCustom {
    fun countByLlmVendorId(llmVendorId: UUID): Long
    fun countByWorkspaceId(workspaceId: UUID): Long

//    fun findWorkspaceIdById(apiKeyId: UUID): UUID?
}
