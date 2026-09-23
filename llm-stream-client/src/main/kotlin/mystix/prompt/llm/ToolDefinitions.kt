package mystix.prompt.llm

import mystix.prompt.llm.schema.ToolSchemaGenerator

object ToolDefinitions {
    private val schemaGenerator = ToolSchemaGenerator()

    fun from(callback: ToolCallback<*, *>): ToolDefinition {
        return ToolDefinition(
            name = callback.name,
            description = callback.description.ifBlank { null },
            inputType = callback.inputType,
            inputSchema = schemaGenerator.generate(callback.inputType),
            returnDirect = callback.returnDirect,
        )
    }
}
