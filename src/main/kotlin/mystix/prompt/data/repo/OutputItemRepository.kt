package mystix.prompt.data.repo

import mystix.prompt.data.entity.OutputItem
import org.springframework.data.jpa.repository.JpaRepository

interface OutputItemRepository : JpaRepository<OutputItem, Long> {
    fun findByLlmCallLogIdOrderByPositionAsc(llmCallLogId: java.util.UUID): List<OutputItem>
}
