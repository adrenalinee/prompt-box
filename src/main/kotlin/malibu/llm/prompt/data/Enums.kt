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

/**
 * 플랫폼 롤. 앞에 있을수록 상위 롤이고, 상위 롤은 하위 롤의 모든 권한을 가진다.
 */
enum class PlatformRole {
    ADMIN,
    USER;

    fun includes(other: PlatformRole): Boolean = ordinal <= other.ordinal
}

/**
 * 워크스페이스 롤. 앞에 있을수록 상위 롤이고, 상위 롤은 하위 롤의 모든 권한을 가진다.
 * - 플랫폼 ADMIN 은 멤버가 아니어도 모든 워크스페이스에서 ADMIN 으로 간주한다. (WorkspaceAuthz)
 */
enum class WorkspaceRole {
    ADMIN,
    USER,
    GUEST;

    fun includes(other: WorkspaceRole): Boolean = ordinal <= other.ordinal
}
