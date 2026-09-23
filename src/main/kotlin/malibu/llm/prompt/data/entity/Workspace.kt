package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import org.hibernate.annotations.UuidGenerator
import java.util.*

@Entity
@Table(name = "workspace")
class Workspace(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "default_model_id", nullable = true)
    var defaultModel: LlmModel? = null,
) : BaseTimestamps()
