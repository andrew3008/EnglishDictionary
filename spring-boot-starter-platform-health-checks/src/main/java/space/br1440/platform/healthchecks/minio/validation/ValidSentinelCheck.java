package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = SentinelCheckValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSentinelCheck {

    String message() default "Sentinel bucket must be specified when sentinel check is enabled";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}

