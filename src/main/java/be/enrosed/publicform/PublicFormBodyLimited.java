package be.enrosed.publicform;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the small anonymous JSON cap to matched form resources, not URL spelling.
 * A resource method (or its class) may declare its own cap with maxBytes; body() then
 * names the class the filter pre-parses into, and Void.class means cap only: no
 * pre-parse, and an empty body is legal.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PublicFormBodyLimited {
    int maxBytes() default 0;

    Class<?> body() default Void.class;
}
