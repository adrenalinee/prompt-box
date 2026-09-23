package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmVendor
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmVendorRepository : JpaRepository<LlmVendor, UUID> {
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<LlmVendor>
}
