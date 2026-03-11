package space.br1440.platform.healthchecks.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

public class PositiveDurationValidator implements ConstraintValidator<PositiveDuration, Duration> {

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        return (value == null) || (!value.isNegative() && !value.isZero());
    }

}
