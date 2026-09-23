package mystix.prompt.llm

enum class ReasoningEffortType {
    /**
     * gpt 5.1 이하는 사용불가.
     */
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

enum class SummaryType {
    /**
     * for gemini
     */
    NONE,
    AUTO,
    CONCISE,
    DETAILED
}

enum class VerbosityType {
    LOW,
    MEDIUM,
    HIGH,
}