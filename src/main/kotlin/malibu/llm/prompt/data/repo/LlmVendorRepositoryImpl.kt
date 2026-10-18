package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.LlmVendorSearchSpec
import malibu.llm.prompt.data.entity.LlmVendor
import malibu.llm.prompt.data.entity.QLlmVendor.Companion.llmVendor
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

class LlmVendorRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : LlmVendorRepositoryCustom {

    override fun searchVendors(
        searchSpec: LlmVendorSearchSpec,
        pageable: Pageable,
    ): Page<LlmVendor> {
        val content = queryFactory.selectFrom(llmVendor)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(llmVendor.count()).from(llmVendor)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, llmVendor, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: LlmVendorSearchSpec,
    ): JPAQuery<T> {
        return where(
            llmVendor.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
