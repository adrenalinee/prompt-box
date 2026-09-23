package malibu.llm.streamclient.exception

class UnsupportedInputTypeException(type: String) : LlmBaseException(
    message = "지원하지 않는 입력 타입입니다. type: $type",
)