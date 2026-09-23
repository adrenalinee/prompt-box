package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.OutputItemPart
import org.springframework.data.jpa.repository.JpaRepository

interface OutputItemPartRepository : JpaRepository<OutputItemPart, Long> {
    fun findByOutputItemIdInOrderByPartIndexAsc(outputItemIds: List<Long>): List<OutputItemPart>
}
