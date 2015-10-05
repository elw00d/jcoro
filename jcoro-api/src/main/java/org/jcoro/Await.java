package org.jcoro;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Note if change this, it is necessary to sync changes in parsing (using Asm).
 *
 * @author elwood
 */
@Target({ElementType.METHOD, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Await {
    /**
     * Name of method.
     */
    String value() default "";

    /**
     * Signature of method. May be needed to resolve ambiguities.
     * Not required.
     */
    String desc() default "";

    /**
     * Full class name of method owner. May be needed to resolve ambiguities.
     * Not required.
     */
    String owner() default "";

    /**
     * If method cannot be instrumented, this attribute should be set to `false`.
     * Not required, default value is `true`.
     */
    boolean patchable() default true;
}
