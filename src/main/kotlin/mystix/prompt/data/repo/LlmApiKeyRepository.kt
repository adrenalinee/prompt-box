package mystix.prompt.data.repo

import mystix.prompt.data.entity.LlmApiKey
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmApiKeyRepository : JpaRepository<LlmApiKey, UUID> {
    fun findByWorkspaceId(workspaceId: UUID, pageable: Pageable): Page<LlmApiKey>
    fun findByWorkspaceIdAndLlmVendorId(workspaceId: UUID, llmVendorId: UUID, pageable: Pageable): Page<LlmApiKey>
    fun findByWorkspaceIdAndNameContainingIgnoreCase(
        workspaceId: UUID,
        name: String,
        pageable: Pageable,
    ): Page<LlmApiKey>
    fun findByWorkspaceIdAndLlmVendorIdAndNameContainingIgnoreCase(
        workspaceId: UUID,
        llmVendorId: UUID,
        name: String,
        pageable: Pageable,
    ): Page<LlmApiKey>
    fun findByLlmVendorId(llmVendorId: UUID, pageable: Pageable): Page<LlmApiKey>
    fun findByLlmVendorIdAndNameContainingIgnoreCase(
        llmVendorId: UUID,
        name: String,
        pageable: Pageable,
    ): Page<LlmApiKey>
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<LlmApiKey>
    fun countByLlmVendorId(llmVendorId: UUID): Long
    fun countByWorkspaceId(workspaceId: UUID): Long
}
