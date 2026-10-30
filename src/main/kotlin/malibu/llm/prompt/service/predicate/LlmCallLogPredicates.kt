package malibu.llm.prompt.service.predicate

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.Predicate
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.entity.QLlmCallLog
import malibu.llm.prompt.data.entity.QPromptRef.Companion.promptRef

object LlmCallLogPredicates {
    private val llmCallLog = QLlmCallLog.llmCallLog
    fun searchLogs(
        searchSpec: LlmCallSearchSpec,
    ): Predicate {
        return BooleanBuilder().apply {
            searchSpec.workspaceId?.also { and(llmCallLog.workspace.id.eq(it)) }
            searchSpec.modelId?.also { and(QLlmCallLog.llmCallLog.llmModelId.eq(it)) }
            searchSpec.status?.also { and(QLlmCallLog.llmCallLog.status.eq(it)) }
            searchSpec.promptId?.also { and(promptRef.prompt.id.eq(it)) }
        }
    }
}