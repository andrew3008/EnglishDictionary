package space.br1440.platform.healthchecks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAsyncTimeoutValidator.class)
@Documented
public @interface ValidAsyncTimeout {

    String message() default "platform.health.minio.async.timeout must be less than maxAge";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
