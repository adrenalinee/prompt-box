package malibu.llm.streamclient.exception

open class LlmBaseException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)