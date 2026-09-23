package malibu.llm.prompt.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebPagingConfig : WebMvcConfigurer {
//    override fun addCorsMappings(registry: CorsRegistry) {
//        registry.addMapping("/**")
//            .allowedOrigins("*")
//            .allowedMethods("*")
//            .allowedHeaders("*")
//    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        val pageableResolver = PageableHandlerMethodArgumentResolver()
        pageableResolver.setMaxPageSize(100)
        resolvers.add(pageableResolver)
    }
}
