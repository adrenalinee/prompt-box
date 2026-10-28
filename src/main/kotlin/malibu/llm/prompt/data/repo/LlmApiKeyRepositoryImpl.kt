package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.data.entity.QLlmApiKey.Companion.llmApiKey
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

class LlmApiKeyRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : LlmApiKeyRepositoryCustom {

    override fun searchApiKeys(
        searchSpec: LlmApiKeySearchSpec,
        pageable: Pageable,
    ): Page<LlmApiKey> {
        val content = queryFactory.selectFrom(llmApiKey)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(llmApiKey.count()).from(llmApiKey)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, llmApiKey, count)
    }

    override fun findWorkspaceIdById(apiKeyId: UUID): UUID? =
        queryFactory.select(llmApiKey.workspace.id).from(llmApiKey)
            .where(llmApiKey.id.eq(apiKeyId))
            .fetchOne()

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: LlmApiKeySearchSpec,
    ): JPAQuery<T> {
        return where(
            searchSpec.workspaceId?.let { llmApiKey.workspace.id.eq(it) },
            searchSpec.vendorId?.let { llmApiKey.llmVendor.id.eq(it) },
            llmApiKey.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
