package malibu.llm.streamclient

import com.openai.models.responses.ResponseCreateParams
import io.swagger.v3.oas.annotations.media.Schema

data class LlmStreamRequest(
    /**
     * 대화 이어가기를 하기 위한 대화기록 전체를 관리해주는 id.
     * 현재 openai 만 지원됨.
     */
    val conversationId: String? = null,

    /**
     * 대화 이어가기를 하기위한 이전 응답의 id
     * openai, xai, google 에서 지원확인.
     * google api 의 경우 previous_interaction_id 라는 이름의 필드임.
     */
    val previousResponseId: String? = null,
    val modelKey: String? = null,
    val apiKey: String? = null,
    val instructions: String? = null,
    val inputs: List<LlmStreamInputItem>? = null,
    val options: LlmStreamOptions? = null,
    val toolChoice: ResponseCreateParams.ToolChoice? = null,
    val tools: List<LlmToolSpec> = emptyList(),
)

abstract class LlmStreamInputItem(
    val type: InputType,
) {
    enum class InputType {
        MESSAGE,
    }
}

data class LlmMessageInputItem(
    val content: String,
    val role: Role = Role.USER,
) : LlmStreamInputItem(InputType.MESSAGE) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM,
        DEVELOPER,
    }
}

data class LlmStreamOptions(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Double? = null,
    val includeThoughts: Boolean? = null,
    val maxTokens: Long? = null,
    val textFormat: String? = null,
    val effort: ReasoningEffortType? = null,
    val verbosity: VerbosityType? = null,
    val summary: SummaryType? = null,
    val store: Boolean? = null,
    val maxBufferedEvents: Int? = null,
)

//event depth
//  response created
//    item added (message|reasoning)
//       part added (summary_text|output_text)
//          text delta(message|reasoning)
//          text done
//       part done
//    item done
//  response done


@Schema(hidden = true)
sealed interface LlmStreamEvent {
    val type: String

    @Schema(
        name = "LlmLogCreatedEvent",
        description = "Emitted when a call log is created.",
    )
    data class LogCreated(
        @param:Schema(example = "log.created")
        override val type: String = "log.created",
        @param:Schema(description = "Call log ID.", example = "00000000-0000-0000-0000-000000000000")
        val logId: String,
        @param:Schema(description = "Creation timestamp (ISO-8601).", example = "2026-01-27T12:34:56Z")
        val createdAt: String?,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmResponseCreatedEvent",
        description = "Emitted when the provider creates a streaming response.",
    )
    data class ResponseCreated(
        @param:Schema(example = "response.created")
        override val type: String = "response.created",
        @param:Schema(description = "Response ID.", example = "resp_123")
        val id: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,

        val model: String? = null,
    ) : LlmStreamEvent


    @Schema(
        name = "LlmResponseCompletedEvent",
        description = "Emitted when the response is completed and usage is available.",
    )
    data class ResponseCompleted(
        @param:Schema(example = "response.completed")
        override val type: String = "response.completed",
        @param:Schema(description = "Response ID.", example = "resp_123")
        val id: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Input token count.", example = "120")
        val inputTokens: Long?,
        @param:Schema(description = "Cached input token count.", example = "20")
        val cachedTokens: Long?,
        @param:Schema(description = "Output token count.", example = "80")
        val outputTokens: Long?,
        @param:Schema(description = "Reasoning token count.", example = "60")
        val reasoningTokens: Long?,
        @param:Schema(description = "Total token count.", example = "200")
        val totalTokens: Long?,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmMessageAddedEvent",
        description = "Emitted when a new message item starts streaming.",
    )
    data class ResponseMessageAdded(
        @param:Schema(example = "response.message.added")
        override val type: String = "response.message.added",
        @param:Schema(description = "Message item ID.", example = "msg_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the message item in the output list.", example = "0")
        val itemIndex: Long,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmMessageDoneEvent",
        description = "Emitted when a message item finishes streaming.",
    )
    data class ResponseMessageDone(
        @param:Schema(example = "response.message.done")
        override val type: String = "response.message.done",
        @param:Schema(description = "Message item ID.", example = "msg_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the delta.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the message item in the output list.", example = "0")
        val itemIndex: Long,
    ) : LlmStreamEvent


    @Schema(
        name = "LlmMessageContentPartAddedEvent",
        description = "Emitted when a message content part starts streaming.",
    )
    data class MessageContentPartAdded(
        @param:Schema(example = "response.message.content_part.added")
        override val type: String = "response.message.content_part.added",
        @param:Schema(description = "Message item ID.", example = "msg_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long? = null,
        @param:Schema(description = "Index of the message item in the output list.", example = "0")
        val itemIndex: Long? = null,
        @param:Schema(description = "Index of the content part within the message.", example = "0")
        val partIndex: Long,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmMessageContentPartDoneEvent",
        description = "Emitted when a message content part finishes streaming.",
    )
    data class MessageContentPartDone(
        @param:Schema(example = "response.message.content_part.done")
        override val type: String = "response.message.content_part.done",
        @param:Schema(description = "Message item ID.", example = "msg_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long? = null,
        @param:Schema(description = "Index of the message item in the output list.", example = "0")
        val itemIndex: Long? = null,
        @param:Schema(description = "Index of the content part within the message.", example = "0")
        val partIndex: Long,
        @param:Schema(description = "Final content part text.", example = "content part complete")
        val text: String? = null,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmMessageDeltaEvent",
        description = "Emitted when a delta chunk is received for a message item.",
    )
    data class MessageDelta(
        @param:Schema(example = "response.message.delta")
        override val type: String = "response.message.delta",
        @param:Schema(description = "Message item ID.", example = "msg_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the delta.", example = "1")
        val sequence: Long? = null,
        @param:Schema(description = "Index of the message item in the output list.", example = "0")
        val itemIndex: Long? = null,
        @param:Schema(description = "Index of the content part within the message.", example = "0")
        val partIndex: Long,
        @param:Schema(description = "Delta text chunk.", example = "Hello")
        val delta: String,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmFunctionCallDoneEvent",
        description = "Emitted once per function call after the client finishes executing it. " +
            "On success, output and delivery are populated. On failure, output is null and errorMessage is populated.",
    )
    data class FunctionCallObserved(
        @param:Schema(example = "response.function_call.done")
        override val type: String = "response.function_call.done",
        @param:Schema(description = "Function call ID when provided by the model.", example = "call_123")
        val callId: String? = null,
        @param:Schema(description = "Function tool name.", example = "lookupWeather")
        val toolName: String,
        @param:Schema(description = "Sequence number assigned to the model's function call request when available.", example = "10")
        val sequence: Long? = null,
        @param:Schema(description = "Function call input encoded as JSON.", example = "{\"city\":\"seoul\"}")
        val argumentsJson: String? = null,
        @param:Schema(description = "Serialized tool output returned by the callback. Null when the call failed.", example = "{\"temperature\":21}")
        val output: String? = null,
        @param:Schema(description = "How the tool result is delivered after execution. Null when the call failed.")
        val delivery: Delivery? = null,
        @param:Schema(description = "Failure message when tool execution or parsing fails.", example = "Tool lookupWeather returned no result")
        val errorMessage: String? = null,
    ) : LlmStreamEvent {
        enum class Delivery {
            TO_MODEL,
            RETURN_DIRECT,
        }
    }

//    enum class ReasoningPart {
//        TEXT,
//        SUMMARY,
//    }

    @Schema(
        name = "LlmReasoningAddedEvent",
        description = "Emitted when a reasoning item starts streaming.",
    )
    data class ResponseReasoningAdded(
        @param:Schema(example = "response.reasoning.added")
        override val type: String = "response.reasoning.added",
        @param:Schema(description = "Reasoning item ID.", example = "reason_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the reasoning item in the output list.", example = "1")
        val itemIndex: Long,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmReasoningDoneEvent",
        description = "Emitted when a reasoning item finishes streaming.",
    )
    data class ResponseReasoningDone(
        @param:Schema(example = "response.reasoning.done")
        override val type: String = "response.reasoning.done",
        @param:Schema(description = "Reasoning item ID.", example = "reason_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the reasoning item in the output list.", example = "1")
        val itemIndex: Long,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmReasoningSummaryPartAddedEvent",
        description = "Emitted when a reasoning summary part is added.",
    )
    data class ReasoningSummaryPartAdded(
        @param:Schema(example = "response.reasoning.summary_part.added")
        override val type: String = "response.reasoning.summary_part.added",
        @param:Schema(description = "Reasoning item ID.", example = "reason_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the reasoning item in the output list.", example = "1")
        val itemIndex: Long,
        @param:Schema(description = "Index of the summary part within the reasoning summary.", example = "0")
        val partIndex: Long,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmReasoningSummaryPartDoneEvent",
        description = "Emitted when a reasoning summary part is completed.",
    )
    data class ReasoningSummaryPartDone(
        @param:Schema(example = "response.reasoning.summary_part.done")
        override val type: String = "response.reasoning.summary_part.done",
        @param:Schema(description = "Reasoning item ID.", example = "reason_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the reasoning item in the output list.", example = "1")
        val itemIndex: Long,
        @param:Schema(description = "Index of the summary part within the reasoning summary.", example = "0")
        val partIndex: Long,
        @param:Schema(description = "Final summary part text.", example = "Summary complete")
        val text: String,
    ) : LlmStreamEvent

    @Schema(
        name = "LlmReasoningDeltaEvent",
        description = "Emitted when a delta chunk is received for a reasoning item.",
    )
    data class ReasoningDelta(
        @param:Schema(example = "response.reasoning.delta")
        override val type: String = "response.reasoning.delta",
        @param:Schema(description = "Reasoning item ID.", example = "reason_123")
        val itemId: String,
        @param:Schema(description = "Sequence number for the event.", example = "1")
        val sequence: Long,
        @param:Schema(description = "Index of the reasoning item in the output list.", example = "1")
        val itemIndex: Long,
        @param:Schema(description = "Index of the summary part within the reasoning summary.", example = "0")
        val partIndex: Long,
        @param:Schema(description = "Delta text chunk.", example = "Thinking")
        val delta: String,
    ) : LlmStreamEvent

}
