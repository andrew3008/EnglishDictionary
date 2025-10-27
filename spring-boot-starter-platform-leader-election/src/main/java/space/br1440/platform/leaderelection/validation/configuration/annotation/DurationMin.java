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
@Constraint(validatedBy = DurationMinValidator.class)
@Documented
public @interface DurationMin {

    String message() default "Длительность должна быть не менее {value}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    long millis() default 0;

    long seconds() default 0;

    long minutes() default 0;

}
