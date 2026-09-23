package malibu.llm.streamclient

import kotlin.reflect.KClass

data class ToolDefinition(
    val name: String,
    val description: String?,
    val inputType: KClass<*>,
    val inputSchema: Map<String, Any?>,
    val returnDirect: Boolean,
)
