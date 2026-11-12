package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.OrganizationStatus
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(name = "organization")
class Organization(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    /** ACTIVE 가 아니면 이 org 와 하위 workspace 의 모든 권한 판정이 거부된다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OrganizationStatus = OrganizationStatus.ACTIVE,
) : BaseTimestamps()
