package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.InputItem
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InputItemRepository : JpaRepository<InputItem, Long> {
    fun findByLlmCallLogIdOrderByPositionAsc(llmCallLogId: UUID): List<InputItem>
}
