// src/main/kotlin/com/example/mostprompt/domain/enums/Enums.kt
package malibu.llm.prompt.data

enum class PromptRefType {
    BRANCH,
    TAG,
}

enum class InputRole {
    USER,
    ASSISTANT,
    SYSTEM,
    DEVELOPER,
}

enum class LlmCallStatus {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED,
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED,
}

//enum class ReasoningEffortType {
//    NONE,
//    MINIMAL,
//    LOW,
//    MEDIUM,
//    HIGH,
//    XHIGH,
//}

//enum class VerbosityType {
//    LOW,
//    MEDIUM,
//    HIGH,
//}

//enum class SummaryType {
//    AUTO,
//    CONCISE,
//    DETAILED
//}

enum class LlmModelType {
    STANDARD,
    REASONING,
    UNKNOWN,
}

enum class OutputItemType {
    MESSAGE,
    REASONING,
    UNKNOWN,
}

enum class OutputItemPartType {
    MESSAGE_CONTENT,
    REASONING_SUMMARY,
    UNKNOWN,
}
