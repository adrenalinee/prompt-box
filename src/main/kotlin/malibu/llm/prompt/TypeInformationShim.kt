package org.springframework.data.util

/**
 * Compatibility shim for libraries expecting the pre-Spring Data 4 package.
 */
interface TypeInformation<T : Any> : org.springframework.data.core.TypeInformation<T>
