package malibu.llm.prompt.data.dto

/**
 * PATCH 요청 필드의 상태. 평범한 nullable 필드로는 "보내지 않음"과 "null 을 보냄"을 구별할 수 없어서
 * 값을 다시 null 로 되돌릴 수 없다. 이 타입은 둘을 구별한다.
 *
 * - JSON 에 키가 없으면 [Absent]: 바꾸지 않는다.
 * - JSON 에 키가 있으면 [Value]: 그 값으로 바꾼다. `null` 이면 [Value] 의 `value` 가 null 이다(지우기).
 *
 * null 로 지울 수 있는 필드에만 `Patch<T?>` 로 쓴다. 필수 필드는 평범한 nullable(null = 변경 없음)로 둔다.
 * 역직렬화는 `PatchJacksonConfig` 에서 등록한다. DTO 에서는 기본값을 [Absent] 로 둔다.
 */
sealed interface Patch<out T> {

    /** 값이 지정됐는지(null 포함). SpEL 에서는 `#request.field.isPresent()` 로 쓴다. */
    val isPresent: Boolean
        get() = this is Value<*>

    data object Absent : Patch<Nothing>

    data class Value<out T>(val value: T) : Patch<T>

    companion object {
        fun <T> of(value: T): Patch<T> = Value(value)
    }
}

/** 값이 지정됐을 때만(null 포함) [block] 을 실행한다. */
inline fun <T> Patch<T>.ifPresent(block: (T) -> Unit) {
    if (this is Patch.Value) block(value)
}
