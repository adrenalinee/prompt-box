package malibu.llm.prompt.config

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ContextConfigurationAttributes
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.ContextCustomizerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

class EntityManagerExceptionTranslationContextCustomizerFactory : ContextCustomizerFactory {
    override fun createContextCustomizer(
        testClass: Class<*>,
        configAttributes: List<ContextConfigurationAttributes>,
    ): ContextCustomizer =
        ContextCustomizer { context, _ ->
            val enabled = context.environment.getProperty("app.exception-translation.em.override") == "true"
            if (!enabled) {
                return@ContextCustomizer
            }
            context.beanFactory.addBeanPostProcessor(EntityManagerExceptionTranslationPostProcessor())
        }
}

private class EntityManagerExceptionTranslationPostProcessor : BeanPostProcessor {
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
