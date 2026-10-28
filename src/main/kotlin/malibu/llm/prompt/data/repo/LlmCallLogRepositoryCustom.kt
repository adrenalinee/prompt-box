package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.entity.LlmCallLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface LlmCallLogRepositoryCustom {
    /**
     * 호출 로그 검색. null 인 조건은 무시한다.
     */
    fun searchLogs(
        searchSpec: LlmCallSearchSpec,
        pageable: Pageable,
    ): Page<LlmCallLog>

    /**
     * 호출 로그의 workspaceId, 실행자 userId. 로그가 없으면 null. (권한 검사용)
     */
    fun findOwnershipById(callLogId: UUID): CallLogOwnership?
}

data class CallLogOwnership(
    val workspaceId: UUID,
    val userId: UUID?,
)
