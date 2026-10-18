package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.QLlmCallLog.Companion.llmCallLog
import malibu.llm.prompt.data.entity.QPromptRef.Companion.promptRef
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

class LlmCallLogRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : LlmCallLogRepositoryCustom {

    override fun searchLogs(
        searchSpec: LlmCallSearchSpec,
        pageable: Pageable,
    ): Page<LlmCallLog> {
        val content = queryFactory.selectFrom(llmCallLog)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(llmCallLog.count()).from(llmCallLog)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, llmCallLog, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: LlmCallSearchSpec,
    ): JPAQuery<T> {
        // promptId 조건이 있을 때만 promptRef 를 조인한다. (조건이 있으면 promptRef 가 null 인 로그는 어차피 제외됨)
        if (searchSpec.promptId != null) {
            join(llmCallLog.promptRef, promptRef)
        }
        return where(
            searchSpec.workspaceId?.let { llmCallLog.workspace.id.eq(it) },
            searchSpec.modelId?.let { llmCallLog.llmModelId.eq(it) },
            searchSpec.status?.let { llmCallLog.status.eq(it) },
            searchSpec.promptId?.let { promptRef.prompt.id.eq(it) }
        )
    }
}
