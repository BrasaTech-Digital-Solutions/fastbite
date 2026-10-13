package es.brasatech.fastbite.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An owner action that only production takes when the environments are linked: creating or removing
 * locations, subscriptions and billing, custom domains. A linked sandbox sends the owner to production
 * instead, so a location never exists only in the sandbox and nobody pays there. See
 * {@link ProductionOnlyConfig}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ProductionOnly {
}
