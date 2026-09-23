package mystix.prompt.data.repo

import mystix.prompt.data.entity.InputItem
import org.springframework.data.jpa.repository.JpaRepository

interface InputItemRepository : JpaRepository<InputItem, Long> {
    fun findByLlmCallLogIdOrderByPositionAsc(llmCallLogId: java.util.UUID): List<InputItem>
}
