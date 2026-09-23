package mystix.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import mystix.prompt.data.BaseTimestamps
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(name = "llm_vendor")
class LlmVendor(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,
) : BaseTimestamps()
