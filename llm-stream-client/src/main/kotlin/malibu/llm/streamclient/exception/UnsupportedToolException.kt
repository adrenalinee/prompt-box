package malibu.llm.streamclient.exception

class UnsupportedToolException(
    provider: String,
    toolTypes: Collection<String>,
) : LlmBaseException(
    message = "지원하지 않는 툴입니다. provider: $provider, toolTypes: ${toolTypes.joinToString(",")}",
)
