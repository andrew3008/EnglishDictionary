package space.br1440.platform.healthchecks.kafka.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = ConsumerCheckValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidConsumerCheck {

    String message() default "Consumer group ID must be specified when consumer check is enabled";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
