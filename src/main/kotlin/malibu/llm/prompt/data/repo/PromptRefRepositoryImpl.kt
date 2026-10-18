package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.PromptRefSearchSpec
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.data.entity.QPromptRef.Companion.promptRef
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

class PromptRefRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : PromptRefRepositoryCustom {

    override fun searchRefs(
        searchSpec: PromptRefSearchSpec,
        pageable: Pageable,
    ): Page<PromptRef> {
        val content = queryFactory.selectFrom(promptRef)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(promptRef.count()).from(promptRef)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, promptRef, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: PromptRefSearchSpec,
    ): JPAQuery<T> {
        return where(
            searchSpec.promptId?.let { promptRef.prompt.id.eq(it) },
            searchSpec.type?.let { promptRef.type.eq(it) },
            promptRef.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
