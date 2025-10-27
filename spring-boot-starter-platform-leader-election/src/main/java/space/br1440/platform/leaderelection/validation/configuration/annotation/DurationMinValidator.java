package space.br1440.platform.leaderelection.validation.configuration.annotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

public class DurationMinValidator implements ConstraintValidator<DurationMin, Duration> {

    private long minMillis;

    @Override
    public void initialize(DurationMin constraintAnnotation) {
        this.minMillis = Duration
                .ofMillis(constraintAnnotation.millis())
                .plusSeconds(constraintAnnotation.seconds())
                .plusMinutes(constraintAnnotation.minutes())
                .toMillis();
    }

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.toMillis() >= minMillis;
    }

}
