package mystix.prompt.config

import malibu.tracer.webmvc.TracerWebMvcConfigurer
import malibu.tracer.webmvc.TracerWebMvcContextApplyer
import org.springframework.stereotype.Component

@Component
class TracerConfig(

): TracerWebMvcConfigurer {
    override fun configureTracerWebMvc(context: TracerWebMvcContextApplyer) {
//        context.traceRequestBody = true
//        context.traceRequestHeaders = true
//        context.traceResponseBody = true
    }
}