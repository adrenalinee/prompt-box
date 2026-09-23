package mystix.prompt.llm.exception

open class LlmBaseException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)