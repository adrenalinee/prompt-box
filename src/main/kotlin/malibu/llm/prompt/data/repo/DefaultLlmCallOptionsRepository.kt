package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DefaultLlmCallOptionsRepository : JpaRepository<DefaultLlmCallOptions, UUID> {
    fun findByWorkspaceId(workspaceId: UUID): DefaultLlmCallOptions?
}
