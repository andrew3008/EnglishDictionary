package space.br1440.platform.healthchecks.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import space.br1440.platform.healthchecks.async.AsyncHealthRefreshPolicy;

import java.time.Duration;

public class ValidAsyncTimeoutValidator implements ConstraintValidator<ValidAsyncTimeout, AsyncHealthRefreshPolicy> {

    @Override
    public boolean isValid(AsyncHealthRefreshPolicy async, ConstraintValidatorContext context) {
        if (async == null || !async.isEnabled()) {
            return true;
        }

        Duration checkTimeout = async.getHealthCheckTimeout();
        Duration staleTtl = async.getStaleResultTtl();

        if (checkTimeout == null || staleTtl == null) {
            return true;
        }

        if (!checkTimeout.minus(staleTtl).isNegative()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                            "healthCheckTimeout (%s) must be less than staleResultTtl (%s)"
                                    .formatted(checkTimeout, staleTtl))
                    .addPropertyNode("healthCheckTimeout")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
