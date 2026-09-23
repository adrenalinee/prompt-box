package mystix.prompt.llm

/**
 * 각 벤더 raw 이벤트를 처리한 결과의 내부 표현.
 *
 * 한 벤더 이벤트가 0개 또는 N개의 StepEmission으로 매핑된다. 이 시퀀스를 concatMap으로 직렬화하면
 * tool 실행 같은 async hop을 거쳐도 순서가 보장된다:
 *  - PassThrough: 그대로 외부 LlmStreamEvent로 방출.
 *  - ToolInvocation: 즉시 tool 실행 → FunctionCallObserved 방출 → tool 결과를 다음 라운드 input으로 모음.
 *  - EndOfRound: 라운드 마지막. 이 라운드에 ToolInvocation이 있었다면 다음 라운드 재귀, 없었다면 sink 종료.
 */
internal sealed interface StepEmission {
    data class PassThrough(val event: LlmStreamEvent) : StepEmission
    data class ToolInvocation(val call: PendingFunctionCallContract) : StepEmission
    data class EndOfRound(val completed: LlmStreamEvent.ResponseCompleted?) : StepEmission
}

/**
 * 각 벤더의 PendingFunctionCall 데이터 클래스가 구현하는 인터페이스.
 * shared tool execution 헬퍼가 벤더 구현 디테일에 의존하지 않도록 한다.
 */
internal interface PendingFunctionCallContract {
    val callId: String?
    val toolName: String
    val argumentsJson: String
    val requestSequence: Long?
}
