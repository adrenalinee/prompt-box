package mystix.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import mystix.prompt.data.BaseTimestamps
import mystix.prompt.data.OutputItemPartType

@Entity
@Table(name = "output_item_part")
class OutputItemPart(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "output_item_id", nullable = false)
    var outputItem: OutputItem,

    @Enumerated(EnumType.STRING)
    @Column(name = "part_type", nullable = false, length = 20)
    var partType: OutputItemPartType = OutputItemPartType.UNKNOWN,

    @Column(name = "part_index", nullable = false)
    var partIndex: Long,

//    @Column(name = "sequence", nullable = false)
//    var sequence: Long,

    @Column(name = "text", nullable = true, columnDefinition = "text")
    var text: String? = null,
) : BaseTimestamps()
