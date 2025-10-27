package space.br1440.platform.leaderelection.validation.configuration.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DurationMaxValidator.class)
@Documented
public @interface DurationMax {

    String message() default "Duration не должен превышать {value}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    long millis() default Long.MAX_VALUE;

    long seconds() default 0;

    long minutes() default 0;

}

