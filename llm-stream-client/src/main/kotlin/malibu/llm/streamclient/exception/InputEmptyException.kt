package malibu.llm.streamclient.exception

class InputEmptyException : LlmBaseException(
    message = "입력 메시지가 비어있습니다.",
)