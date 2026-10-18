package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmModel
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmModelRepository : JpaRepository<LlmModel, Long>, LlmModelRepositoryCustom {
    fun countByLlmVendorId(llmVendorId: UUID): Long
}
