package mystix.prompt.config

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataIntegrityViolationException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

@AutoConfiguration
class EntityManagerExceptionTranslationConfig {

    @Bean
    @ConditionalOnProperty(name = ["app.exception-translation.em.override"], havingValue = "true")
    fun entityManagerExceptionTranslator(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (bean !is EntityManager) {
                    return bean
                }

                return Proxy.newProxyInstance(
                    EntityManager::class.java.classLoader,
                    arrayOf(EntityManager::class.java),
                ) { _, method, args ->
                    try {
                        if (args == null) {
                            method.invoke(bean)
                        } else {
                            method.invoke(bean, *args)
                        }
                    } catch (ex: InvocationTargetException) {
                        val target = ex.targetException
                        if (target is ConstraintViolationException) {
                            throw DataIntegrityViolationException("constraint violation", target)
                        }
                        if (target is PersistenceException && target.cause is ConstraintViolationException) {
                            throw DataIntegrityViolationException("constraint violation", target.cause)
                        }
                        throw target
                    } catch (ex: ConstraintViolationException) {
                        throw DataIntegrityViolationException("constraint violation", ex)
                    } catch (ex: PersistenceException) {
                        if (ex.cause is ConstraintViolationException) {
                            throw DataIntegrityViolationException("constraint violation", ex.cause)
                        }
                        throw ex
                    }
                }
            }
        }
}
