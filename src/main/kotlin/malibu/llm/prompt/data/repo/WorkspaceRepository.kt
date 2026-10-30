package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Workspace
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface WorkspaceRepository
    : JpaRepository<Workspace, UUID>, QuerydslPredicateExecutor<Workspace>, WorkspaceRepositoryCustom
