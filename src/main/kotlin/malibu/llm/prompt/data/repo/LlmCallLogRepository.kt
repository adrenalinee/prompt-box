package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.entity.LlmCallLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface LlmCallLogRepository : JpaRepository<LlmCallLog, UUID> {
    @Query(
        value = """
            select l from LlmCallLog l
            left join l.promptRef pr
            left join pr.prompt p
            where (:workspaceId is null or l.workspace.id = :workspaceId)
              and (:modelId is null or l.llmModelId = :modelId)
              and (:status is null or l.status = :status)
              and (:promptId is null or p.id = :promptId)
        """,
        countQuery = """
            select count(l) from LlmCallLog l
            left join l.promptRef pr
            left join pr.prompt p
            where (:workspaceId is null or l.workspace.id = :workspaceId)
              and (:modelId is null or l.llmModelId = :modelId)
              and (:status is null or l.status = :status)
              and (:promptId is null or p.id = :promptId)
        """,
    )
    fun searchLogs(
        @Param("workspaceId") workspaceId: UUID?,
        @Param("modelId") modelId: Long?,
        @Param("status") status: LlmCallStatus?,
        @Param("promptId") promptId: UUID?,
        pageable: Pageable,
    ): Page<LlmCallLog>

    fun findByWorkspaceId(workspaceId: UUID, pageable: Pageable): Page<LlmCallLog>
    fun findByWorkspaceIdAndLlmModelId(workspaceId: UUID, llmModelId: Long, pageable: Pageable): Page<LlmCallLog>
    fun findByWorkspaceIdAndStatus(workspaceId: UUID, status: LlmCallStatus, pageable: Pageable): Page<LlmCallLog>
    fun findByWorkspaceIdAndLlmModelIdAndStatus(
        workspaceId: UUID,
        llmModelId: Long,
        status: LlmCallStatus,
        pageable: Pageable,
    ): Page<LlmCallLog>
    fun findByLlmModelId(llmModelId: Long, pageable: Pageable): Page<LlmCallLog>
    fun findByStatus(status: LlmCallStatus, pageable: Pageable): Page<LlmCallLog>
    fun findByLlmModelIdAndStatus(
        llmModelId: Long,
        status: LlmCallStatus,
        pageable: Pageable,
    ): Page<LlmCallLog>
    fun countByLlmModelId(llmModelId: Long): Long
    fun countByLlmApiKeyId(llmApiKeyId: UUID): Long
    fun countByPromptRefId(promptRefId: Long): Long
    fun countByWorkspaceId(workspaceId: UUID): Long
}
