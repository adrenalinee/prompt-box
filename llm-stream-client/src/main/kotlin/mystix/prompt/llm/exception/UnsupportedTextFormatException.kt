package mystix.prompt.llm.exception

class UnsupportedTextFormatException(
    vendorName: String,
    textFormat: String,
) : LlmBaseException(
    message = "현재 vendor 에서 지원하지 않는 textFormat 입니다. vendorName: $vendorName, textFormat: $textFormat",
)
