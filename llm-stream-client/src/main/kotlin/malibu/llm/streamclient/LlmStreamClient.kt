package malibu.llm.streamclient

import reactor.core.publisher.Flux

interface LlmStreamClient {
    val vendorName: String

    fun stream(requestSpec: LlmStreamRequest): Flux<LlmStreamEvent>
}
