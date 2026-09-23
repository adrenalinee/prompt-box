package malibu.llm.prompt.api

import org.springframework.data.domain.Page

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun <T : Any, R> Page<T>.toResponse(mapper: (T) -> R): PageResponse<R> = PageResponse(
    items = content.map(mapper),
    page = number,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)
