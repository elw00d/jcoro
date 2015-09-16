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
    String value() default "";

    // Not required
    String desc() default "";

    // Not required
    boolean patchable() default true;
}
