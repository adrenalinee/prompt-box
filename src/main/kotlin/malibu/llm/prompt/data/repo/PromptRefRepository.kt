package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.PromptRef
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PromptRefRepository : JpaRepository<PromptRef, Long>, PromptRefRepositoryCustom {
    fun findByPromptId(promptId: UUID): List<PromptRef>
    fun countByPromptId(promptId: UUID): Long

    /** 루트(prompt)를 workspace 조건으로 먼저 찾은 뒤 그 prompt 의 ref 만 조회한다. (authz-spec §5) */
    fun findByIdAndPromptId(id: Long, promptId: UUID): PromptRef?

    /** 실행 요청의 promptRefId: 경로의 workspace 에 속한 ref 만. */
    fun findByIdAndPromptWorkspaceId(id: Long, workspaceId: UUID): PromptRef?
}
