package malibu.llm.prompt.data.repo

import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.entity.QWorkspace.Companion.workspace
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

class WorkspaceRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : WorkspaceRepositoryCustom {

    override fun searchWorkspaces(
        searchSpec: WorkspaceSearchSpec,
        pageable: Pageable,
    ): Page<Workspace> {
        val content = queryFactory.selectFrom(workspace)
            .applySearchCondition(searchSpec)
        val count = queryFactory.select(workspace.count()).from(workspace)
            .applySearchCondition(searchSpec)
        return content.fetchPage(pageable, workspace, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: WorkspaceSearchSpec,
    ): JPAQuery<T> {
        return where(
            workspace.name.containsIgnoreCaseOrNull(searchSpec.name)
        )
    }
}
