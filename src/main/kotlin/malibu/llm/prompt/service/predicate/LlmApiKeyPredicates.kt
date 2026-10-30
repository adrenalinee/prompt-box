package malibu.llm.prompt.service.predicate

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.Predicate
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.entity.QLlmApiKey
import java.util.*

object LlmApiKeyPredicates {
    private val llmApiKey = QLlmApiKey.llmApiKey
    fun searchApiKeys(
        searchSpec: LlmApiKeySearchSpec,
    ): Predicate {
        return BooleanBuilder().apply {
            searchSpec.name?.also { and(llmApiKey.name.containsIgnoreCase(it)) }
            searchSpec.workspaceId?.also { and(llmApiKey.workspace.id.eq(it)) }
            searchSpec.vendorId?.also { and(llmApiKey.llmVendor.id.eq(it)) }
        }
    }

    fun findWorkspaceIdById(
        apiKeyId: UUID
    ): Predicate {
        return BooleanBuilder().apply {
            and(llmApiKey.id.eq(apiKeyId))
        }
    }
}