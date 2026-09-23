package mystix.prompt.data.repo

import mystix.prompt.data.entity.LlmModel
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmModelRepository : JpaRepository<LlmModel, Long> {
    fun findByLlmVendorId(llmVendorId: UUID, pageable: Pageable): Page<LlmModel>
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<LlmModel>
    fun findByLlmVendorIdAndNameContainingIgnoreCase(
        llmVendorId: UUID,
        name: String,
        pageable: Pageable,
    ): Page<LlmModel>
    fun countByLlmVendorId(llmVendorId: UUID): Long
}
