package mystix.prompt.llm

import org.reactivestreams.Publisher
import kotlin.reflect.KClass

class ToolCallback<A : Any, R>(
    val name: String,
    val description: String,
    val inputType: KClass<A>,
    val returnDirect: Boolean = false,
    val callback: (A) -> Publisher<R>,
) : LlmToolSpec {
    override val toolType: String = "function"
    override val mergeKey: String = "function:$name"
}
