package malibu.llm.streamclient

import malibu.llm.streamclient.exception.InputEmptyException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class FakeLlmStreamClientTest {

    @Test
    fun `queued responses are emitted in request order`() {
        val client = FakeLlmStreamClient(listOf("first", "second"))

        val firstEvents = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "hello")),
            )
        ).collectList().block().orEmpty()

        val secondEvents = client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "again")),
            )
        ).collectList().block().orEmpty()

        assertEquals(
            listOf(
                "response.created",
                "response.message.added",
                "response.message.content_part.added",
                "response.message.delta",
                "response.message.content_part.done",
                "response.message.done",
                "response.completed",
            ),
            firstEvents.map { it.type }
        )
        assertEquals("first", firstEvents.filterIsInstance<LlmStreamEvent.MessageDelta>().single().delta)
        assertEquals("second", secondEvents.filterIsInstance<LlmStreamEvent.MessageDelta>().single().delta)
        assertEquals(2, client.receivedRequests.size)
    }

    @Test
    fun `fails when usable input is missing`() {
        val client = FakeLlmStreamClient(listOf("unused"))

        StepVerifier.create(client.stream(LlmStreamRequest()))
            .expectErrorSatisfies { error ->
                assertTrue(error is InputEmptyException)
            }
            .verify()
    }

    @Test
    fun `fails when queued responses are exhausted`() {
        val client = FakeLlmStreamClient(listOf("only-once"))

        client.stream(
            LlmStreamRequest(
                inputs = listOf(LlmMessageInputItem(content = "first")),
            )
        ).collectList().block()

        val error = assertThrows(IllegalStateException::class.java) {
            client.stream(
                LlmStreamRequest(
                    inputs = listOf(LlmMessageInputItem(content = "second")),
                )
            ).collectList().block()
        }

        assertTrue(error.message!!.contains("No fake LLM responses remaining"))
    }
}
