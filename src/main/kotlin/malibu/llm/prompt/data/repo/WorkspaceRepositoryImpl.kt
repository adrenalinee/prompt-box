package malibu.llm.prompt.data.repo

import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import malibu.llm.prompt.data.OrganizationStatus
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.QOrganizationMember.Companion.organizationMember
import malibu.llm.prompt.data.entity.QWorkspace.Companion.workspace
import malibu.llm.prompt.data.entity.QWorkspaceMember.Companion.workspaceMember
import malibu.llm.prompt.data.entity.Workspace
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
        accessibleUserId: UUID?,
    ): Page<Workspace> {
        val content = queryFactory.selectFrom(workspace)
            .applySearchCondition(searchSpec, accessibleUserId)
        val count = queryFactory.select(workspace.count()).from(workspace)
            .applySearchCondition(searchSpec, accessibleUserId)
        return content.fetchPage(pageable, workspace, count)
    }

    private fun <T> JPAQuery<T>.applySearchCondition(
        searchSpec: WorkspaceSearchSpec,
        accessibleUserId: UUID?,
    ): JPAQuery<T> {
        return where(
            workspace.name.containsIgnoreCaseOrNull(searchSpec.name),
            searchSpec.organizationId?.let { workspace.organization.id.eq(it) },
            accessibleUserId?.let { accessibleBy(it) },
        )
    }

    private fun accessibleBy(userId: UUID): BooleanExpression {
        val direct = JPAExpressions.selectOne()
            .from(workspaceMember)
            .where(
                workspaceMember.workspace.id.eq(workspace.id),
                workspaceMember.user.id.eq(userId),
            )
            .exists()
        val implied = JPAExpressions.selectOne()
            .from(organizationMember)
            .where(
                organizationMember.organization.id.eq(workspace.organization.id),
                organizationMember.user.id.eq(userId),
                organizationMember.role.impliedWorkspaceRole.id.isNotNull,
            )
            .exists()
        return workspace.organization.status.eq(OrganizationStatus.ACTIVE)
            .and(direct.or(implied))
    }
}
