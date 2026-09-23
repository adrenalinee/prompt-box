package mystix.prompt.data.repo

import mystix.prompt.data.entity.Workspace
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkspaceRepository : JpaRepository<Workspace, UUID> {
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Workspace>
}
