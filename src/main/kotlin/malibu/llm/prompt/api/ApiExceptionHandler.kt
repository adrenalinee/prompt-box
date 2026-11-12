package malibu.llm.prompt.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import malibu.llm.prompt.error.BadRequestException
import malibu.llm.prompt.error.ConflictException
import malibu.llm.prompt.error.ForbiddenException
import malibu.llm.prompt.error.NotFoundException
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
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
        writeApiError(HttpStatus.BAD_REQUEST, null, ex.message, request, response)
    }

    /** 권한 판정 NOT_MEMBER(AuthzNotFoundException) 도 여기서 404 가 된다. */
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(
        ex: NotFoundException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeApiError(HttpStatus.NOT_FOUND, null, ex.message, request, response)
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(
        ex: ConflictException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeApiError(HttpStatus.CONFLICT, null, ex.message, request, response)
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(
        ex: ForbiddenException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeApiError(HttpStatus.FORBIDDEN, ex.code, ex.message, request, response)
    }

    /** @PreAuthorize 판정 결과 DENIED. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        ex: AccessDeniedException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        writeApiError(HttpStatus.FORBIDDEN, FORBIDDEN_CODE, "권한이 없습니다.", request, response)
    }

    companion object {
        const val FORBIDDEN_CODE = "FORBIDDEN"
    }
}

data class ApiErrorResponse(
    val timestamp: String,
    val status: Int,
    val error: String,
    /** 클라이언트가 분기할 때 쓰는 에러 코드. 예: FORBIDDEN, USER_NOT_REGISTERED, USER_DISABLED */
    val code: String?,
    val message: String?,
    val path: String,
)

private val errorJsonMapper = JsonMapper()

/**
 * 에러 응답을 쓴다. 컨트롤러 예외 처리와 보안 필터(인증 실패) 양쪽에서 같은 형식을 쓴다.
 */
fun writeApiError(
    status: HttpStatus,
    code: String?,
    message: String?,
    request: HttpServletRequest,
    response: HttpServletResponse,
) {
    if (response.isCommitted) {
        return
    }
    val body = ApiErrorResponse(
        timestamp = OffsetDateTime.now().toString(),
        status = status.value(),
        error = status.reasonPhrase,
        code = code,
        message = message,
        path = request.requestURI,
    )
    response.status = status.value()
    response.characterEncoding = StandardCharsets.UTF_8.name()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.writer.use { it.write(errorJsonMapper.writeValueAsString(body)) }
}
