package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.entity.Prompt
import malibu.llm.prompt.data.entity.QPrompt.Companion.prompt
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

class PromptRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : PromptRepositoryCustom {

    override fun searchPrompts(
        searchSpec: PromptSearchSpec,
        pageable: Pageable,
    ): Page<Prompt> {
        val content = queryFactory.selectFrom(prompt)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(prompt.count()).from(prompt)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, prompt, count)
    }

    override fun findWorkspaceIdById(promptId: UUID): UUID? =
        queryFactory.select(prompt.workspace.id).from(prompt)
            .where(prompt.id.eq(promptId))
            .fetchOne()

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: PromptSearchSpec,
    ): JPAQuery<T> {
        return where(
            searchSpec.workspaceId?.let { prompt.workspace.id.eq(it) },
            prompt.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
