package space.br1440.platform.leaderelection.validation.configuration.annotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Duration;

public class DurationMaxValidator implements ConstraintValidator<DurationMax, Duration> {

    private long maxMillis;

    @Override
    public void initialize(DurationMax constraintAnnotation) {
        long millis = constraintAnnotation.millis();
        if (millis == Long.MAX_VALUE && (constraintAnnotation.seconds() > 0 || constraintAnnotation.minutes() > 0)) {
            this.maxMillis = Duration
                    .ofSeconds(constraintAnnotation.seconds())
                    .plusMinutes(constraintAnnotation.minutes())
                    .toMillis();
        } else {
            this.maxMillis = millis;
        }
    }

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.toMillis() <= maxMillis;
    }

}

