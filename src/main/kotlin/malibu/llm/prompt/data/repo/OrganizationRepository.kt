package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.Organization
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID>
