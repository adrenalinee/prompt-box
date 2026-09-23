package malibu.llm.prompt.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import malibu.llm.prompt.error.BadRequestException
import malibu.llm.prompt.error.ConflictException
import malibu.llm.prompt.error.NotFoundException
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(
        ex: BadRequestException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        logger.error(ex) { "Bad request" }
        writeErrorResponse(HttpStatus.BAD_REQUEST, ex, request, response)
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(
        ex: NotFoundException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeErrorResponse(HttpStatus.NOT_FOUND, ex, request, response)
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(
        ex: ConflictException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeErrorResponse(HttpStatus.CONFLICT, ex, request, response)
    }

    private fun writeErrorResponse(
        status: HttpStatus,
        ex: RuntimeException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val body = ApiErrorResponse(
            timestamp = OffsetDateTime.now().toString(),
            status = status.value(),
            error = status.reasonPhrase,
            message = ex.message,
            path = request.requestURI,
        )
        if (response.isCommitted) {
            return
        }
        response.status = status.value()
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.use { it.write(JsonMapper().writeValueAsString(body)) }
    }
}

data class ApiErrorResponse(
    val timestamp: String,
    val status: Int,
    val error: String,
    val message: String?,
    val path: String,
)
