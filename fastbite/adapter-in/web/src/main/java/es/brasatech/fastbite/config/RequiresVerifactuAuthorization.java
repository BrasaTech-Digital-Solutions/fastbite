package es.brasatech.fastbite.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks controller endpoints that create orders, initiate/take payments, or issue fiscal tickets
 * and therefore require an active VERI*FACTU authorisation in production.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresVerifactuAuthorization {
}
