package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmApiKey
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmApiKeyRepository : JpaRepository<LlmApiKey, UUID>, LlmApiKeyRepositoryCustom {
    fun countByLlmVendorId(llmVendorId: UUID): Long
    fun countByWorkspaceId(workspaceId: UUID): Long

    /** IDOR 방어. (authz-spec §5) */
    fun findByIdAndWorkspaceId(id: UUID, workspaceId: UUID): LlmApiKey?
}
