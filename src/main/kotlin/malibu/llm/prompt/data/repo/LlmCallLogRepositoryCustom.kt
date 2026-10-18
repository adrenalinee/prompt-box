package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.entity.LlmCallLog
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface LlmCallLogRepositoryCustom {
    /**
     * 호출 로그 검색. null 인 조건은 무시한다.
     */
    fun searchLogs(
        searchSpec: LlmCallSearchSpec,
        pageable: Pageable,
    ): Page<LlmCallLog>
}
