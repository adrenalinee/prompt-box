package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmCallLog
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmCallLogRepository : JpaRepository<LlmCallLog, UUID>, LlmCallLogRepositoryCustom {
    fun countByLlmModelId(llmModelId: Long): Long
    fun countByLlmApiKeyId(llmApiKeyId: UUID): Long
    fun countByPromptRefId(promptRefId: Long): Long
    fun countByWorkspaceId(workspaceId: UUID): Long
}
