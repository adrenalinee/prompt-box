package malibu.llm.streamclient

sealed interface LlmToolSpec {
    val toolType: String
    val mergeKey: String
}

data class LlmFileSearchTool(
    val vectorStoreIds: List<String>,
    val maxNumResults: Long? = null,
) : LlmToolSpec {
    init {
        require(vectorStoreIds.isNotEmpty()) { "vectorStoreIds must not be empty" }
    }

    override val toolType: String = "file_search"
    override val mergeKey: String = toolType
}

internal fun mergeToolSpecs(
    base: List<LlmToolSpec>,
    override: List<LlmToolSpec>,
): List<LlmToolSpec> {
    val merged = linkedMapOf<String, LlmToolSpec>()
    base.forEach { merged[it.mergeKey] = it }
    override.forEach { merged[it.mergeKey] = it }
    return merged.values.toList()
}
