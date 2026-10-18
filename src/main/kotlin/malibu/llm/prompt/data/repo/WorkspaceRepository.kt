package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Workspace
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkspaceRepository : JpaRepository<Workspace, UUID>, WorkspaceRepositoryCustom
