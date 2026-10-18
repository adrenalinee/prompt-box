package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.LlmModelSearchSpec
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.data.entity.QLlmModel.Companion.llmModel
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

class LlmModelRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : LlmModelRepositoryCustom {

    override fun searchModels(
        searchSpec: LlmModelSearchSpec,
        pageable: Pageable,
    ): Page<LlmModel> {
        val content = queryFactory.selectFrom(llmModel)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(llmModel.count()).from(llmModel)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, llmModel, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: LlmModelSearchSpec,
    ): JPAQuery<T> {
        return where(
            searchSpec.vendorId?.let { llmModel.llmVendor.id.eq(it) },
            llmModel.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
