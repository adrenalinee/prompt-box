package malibu.llm.prompt.service.predicate

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.Predicate
import malibu.llm.prompt.data.dto.LlmModelSearchSpec
import malibu.llm.prompt.data.entity.QLlmModel.Companion.llmModel

object LlmModelPredicates {
    fun searchModels(
        searchSpec: LlmModelSearchSpec,
    ): Predicate {
        return BooleanBuilder().apply {
            searchSpec.vendorId?.also { and(llmModel.llmVendor.id.eq(it)) }
            searchSpec.name?.also { and(llmModel.name.containsIgnoreCase(it)) }
        }
    }
}