package mystix.prompt.llm.exception

import mystix.prompt.llm.SummaryType

class UnsupportedSummaryTypeException(vendorName: String, summaryType: SummaryType) : LlmBaseException(
    message = "현재 vendor 에서 지원하지 않는 summary 타입입니다. vendorName: $vendorName, summaryType: $summaryType",
)