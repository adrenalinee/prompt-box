package mystix.prompt.llm.openai

import com.openai.models.responses.ResponseOutputItemDoneEvent
import com.openai.models.responses.ResponseStreamEvent
import malibu.llm.streamclient.exception.InputEmptyException
import malibu.llm.streamclient.exception.ToolArgumentsParseException
import malibu.llm.streamclient.exception.UnsupportedTextFormatException
import malibu.llm.streamclient.LlmMessageInputItem
import malibu.llm.streamclient.LlmStreamOptions
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.streamclient.LlmStreamRequest
import malibu.llm.streamclient.ToolCallback
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenaiLlmStreamClientErrorTests {

    @Test
    fun failsWhenInputsMissing() {
        val client = clientWithStreams(
            defaultRequestSpec = LlmStreamRequest(),
            streams = listOf(fakeStream())
        )

        val outputs = client.stream(LlmStreamRequest())

        StepVerifier.create(outputs)
            .expectErrorSatisfies { error ->
                assertTrue(error is InputEmptyException)
                assertTrue(error.message?.contains("입력 메시지") == true)
            }
            .verify()
    }

    @Test
    fun failsWhenTextFormatCannotBeMappedToOpenAiRequest() {
        val client = clientWithStreams(
            defaultRequestSpec = LlmStreamRequest(),
            streams = listOf(fakeStream())
        )

        assertFailsWith<UnsupportedTextFormatException> {
            client.stream(
                LlmStreamRequest(
                    inputs = listOf(LlmMessageInputItem(content = "bad format")),
                    options = LlmStreamOptions(textFormat = "json_schema"),
                )
            )
        }
    }

    @Test
    fun failsWhenToolNotRegistered() {
        val callId = "missing_tool"
        val client = clientWithStreams(
            streams = listOf(
                fakeStream(
                    ResponseStreamEvent.Companion.ofOutputItemDone(
                        ResponseOutputItemDoneEvent.builder()
                            .item(functionCallOutputItem(callId, "unknownTool", "{}"))
                            .outputIndex(0)
                            .sequenceNumber(0)
                            .build()
                    )
                )
            )
        )

        val outputs = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "call tool")))
        )

        StepVerifier.create(outputs)
            .expectNextMatches { event ->
                event is LlmStreamEvent.FunctionCallObserved &&
                    event.callId == callId &&
                    event.toolName == "unknownTool" &&
                    event.output == null &&
                    event.errorMessage != null
            }
            .expectErrorSatisfies { error ->
                assertTrue(error is IllegalStateException)
                assertTrue(error.message?.contains("No tool callback") == true)
            }
            .verify()
    }

    @Test
    fun failsWhenToolArgumentsInvalid() {
        val toolName = "parseFails"
        val tool = ToolCallback(
            name = toolName,
            description = "",
            inputType = ToolArgs::class,
        ) { Mono.just("ok") }

        val client = clientWithStreams(
            tools = listOf(tool),
            streams = listOf(
                fakeStream(
                    ResponseStreamEvent.Companion.ofOutputItemDone(
                        ResponseOutputItemDoneEvent.builder()
                            .item(functionCallOutputItem("bad_args", toolName, "{invalid"))
                            .outputIndex(0)
                            .sequenceNumber(0)
                            .build()
                    )
                )
            )
        )

        val outputs = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "invalid args")))
        )

        StepVerifier.create(outputs)
            .expectNextMatches { event ->
                event is LlmStreamEvent.FunctionCallObserved &&
                    event.callId == "bad_args" &&
                    event.toolName == toolName &&
                    event.output == null &&
                    event.errorMessage != null
            }
            .expectErrorSatisfies { error ->
                assertTrue(error is ToolArgumentsParseException)
                assertTrue(error.message?.contains("툴 입력 파싱") == true)
            }
            .verify()
    }

    @Test
    fun failsWhenToolReturnsEmptyPublisher() {
        val toolName = "emptyPublisher"
        val tool = ToolCallback(
            name = toolName,
            description = "",
            inputType = ToolArgs::class,
        ) { Mono.empty<String>() }

        val client = clientWithStreams(
            tools = listOf(tool),
            streams = listOf(
                fakeStream(
                    ResponseStreamEvent.Companion.ofOutputItemDone(
                        ResponseOutputItemDoneEvent.builder()
                            .item(functionCallOutputItem("empty", toolName, "{}"))
                            .outputIndex(0)
                            .sequenceNumber(0)
                            .build()
                    )
                )
            )
        )

        val outputs = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "empty?")))
        )

        StepVerifier.create(outputs)
            .expectNextMatches { event ->
                event is LlmStreamEvent.FunctionCallObserved &&
                    event.callId == "empty" &&
                    event.toolName == toolName &&
                    event.output == null &&
                    event.errorMessage != null
            }
            .expectErrorSatisfies { error ->
                assertTrue(error is IllegalStateException)
                assertTrue(error.message?.contains("returned no result") == true)
            }
            .verify()
    }

    @Test
    fun failsWhenToolCallbackThrows() {
        val toolName = "blowUp"
        val tool = ToolCallback(
            name = toolName,
            description = "",
            inputType = ToolArgs::class,
        ) { Mono.error<String>(IllegalStateException("boom")) }

        val client = clientWithStreams(
            tools = listOf(tool),
            streams = listOf(
                fakeStream(
                    ResponseStreamEvent.Companion.ofOutputItemDone(
                        ResponseOutputItemDoneEvent.builder()
                            .item(functionCallOutputItem("explode", toolName, "{}"))
                            .outputIndex(0)
                            .sequenceNumber(0)
                            .build()
                    )
                )
            )
        )

        val outputs = client.stream(
            LlmStreamRequest(inputs = listOf(LlmMessageInputItem(content = "boom")))
        )

        StepVerifier.create(outputs)
            .expectNextMatches { event ->
                event is LlmStreamEvent.FunctionCallObserved &&
                    event.callId == "explode" &&
                    event.toolName == toolName &&
                    event.output == null &&
                    event.errorMessage?.contains("boom") == true
            }
            .expectErrorSatisfies { error ->
                assertTrue(error is IllegalStateException)
                assertTrue(error.message?.contains("boom") == true)
            }
            .verify()
    }
}
