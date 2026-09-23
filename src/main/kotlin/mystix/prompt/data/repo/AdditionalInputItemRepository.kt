package mystix.prompt.data.repo

import mystix.prompt.data.entity.AdditionalInputItem
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface AdditionalInputItemRepository : JpaRepository<AdditionalInputItem, Long> {
    fun findByPromptRefIdOrderByPositionAsc(promptRefId: Long): List<AdditionalInputItem>
    fun findByPromptRefId(promptRefId: Long, pageable: Pageable): Page<AdditionalInputItem>
    fun countByPromptRefId(promptRefId: Long): Long
    fun deleteByPromptRefId(promptRefId: Long)
}
