package malibu.llm.prompt.data.repo

import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.entity.QWorkspace.Companion.workspace
import malibu.llm.prompt.data.entity.QWorkspaceMember.Companion.workspaceMember
import malibu.llm.prompt.data.repo.support.containsIgnoreCaseOrNull
import malibu.llm.prompt.data.repo.support.fetchPage
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

class WorkspaceRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
) : WorkspaceRepositoryCustom {

    override fun searchWorkspaces(
        searchSpec: WorkspaceSearchSpec,
        pageable: Pageable,
        memberUserId: UUID?,
    ): Page<Workspace> {
        val content = queryFactory.selectFrom(workspace)
            .applySearchCondition(searchSpec, memberUserId)
        val count = queryFactory.select(workspace.count()).from(workspace)
            .applySearchCondition(searchSpec, memberUserId)
        return content.fetchPage(pageable, workspace, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: WorkspaceSearchSpec,
        memberUserId: UUID?,
    ): JPAQuery<T> {
        return where(
            workspace.name.containsIgnoreCaseOrNull(searchSpec.name),
            memberUserId?.let {
                JPAExpressions.selectOne().from(workspaceMember)
                    .where(
                        workspaceMember.workspace.id.eq(workspace.id),
                        workspaceMember.user.id.eq(it),
                    )
                    .exists()
            },
        )
    }
}
