package malibu.llm.streamclient.exception

import malibu.llm.streamclient.ReasoningEffortType

class UnsupportedReasoningEffortTypeException(vendorName: String, reasoningEffortType: ReasoningEffortType) : LlmBaseException(
    message = "현재 vendor 에서 지원하지 않는 reasoningEffortType 타입입니다. vendorName: $vendorName, reasoningEffortType: $reasoningEffortType",
)