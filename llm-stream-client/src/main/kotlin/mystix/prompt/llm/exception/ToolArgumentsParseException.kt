package mystix.prompt.llm.exception

class ToolArgumentsParseException(
    toolName: String, cause: Throwable? = null
) : LlmBaseException(
    message = "툴 입력 파싱에 실패했습니다. toolName: $toolName",
    cause = cause,
)