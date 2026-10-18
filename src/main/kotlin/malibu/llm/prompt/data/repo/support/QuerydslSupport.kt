package malibu.llm.prompt.data.repo.support

import com.querydsl.core.types.EntityPath
import com.querydsl.core.types.Expression
import com.querydsl.core.types.Order
import com.querydsl.core.types.OrderSpecifier
import com.querydsl.core.types.dsl.PathBuilder
import com.querydsl.jpa.impl.JPAQuery
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.support.PageableExecutionUtils

/**
 * Pageable 의 Sort 를 querydsl OrderSpecifier 로 변환한다.
 * - property 는 entityPath 기준 경로이며 "promptRef.name" 처럼 점(.)으로 연관 경로를 지정할 수 있다.
 */
@Suppress("UNCHECKED_CAST")
fun Sort.toOrderSpecifiers(entityPath: EntityPath<*>): Array<OrderSpecifier<*>> {
    val root = PathBuilder(entityPath.type, entityPath.metadata)
    return map { order ->
        val path = order.property.split('.').fold(root as PathBuilder<*>) { acc, segment -> acc.get(segment) }
        val nullHandling = when (order.nullHandling) {
            Sort.NullHandling.NULLS_FIRST -> OrderSpecifier.NullHandling.NullsFirst
            Sort.NullHandling.NULLS_LAST -> OrderSpecifier.NullHandling.NullsLast
            else -> OrderSpecifier.NullHandling.Default
        }
        OrderSpecifier(
            if (order.isAscending) Order.ASC else Order.DESC,
            path as Expression<Comparable<Any>>,
            nullHandling,
        )
    }.toList().toTypedArray()
}

/**
 * contentQuery 에 정렬/페이징을 적용해 조회하고 Page 로 감싼다.
 * - 마지막 페이지 등 전체 건수를 알 수 있는 경우 countQuery 는 실행하지 않는다.
 */
fun <T : Any> JPAQuery<T>.fetchPage(
    pageable: Pageable,
    entityPath: EntityPath<*>,
    countQuery: JPAQuery<Long>,
): Page<T> {
    orderBy(*pageable.sort.toOrderSpecifiers(entityPath))
    if (pageable.isPaged) {
        offset(pageable.offset)
        limit(pageable.pageSize.toLong())
    }
    return PageableExecutionUtils.getPage(fetch(), pageable) { countQuery.fetchOne() ?: 0L }
}
