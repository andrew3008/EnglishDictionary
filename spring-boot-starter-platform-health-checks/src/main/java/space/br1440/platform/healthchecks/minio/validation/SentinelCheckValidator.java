package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import space.br1440.platform.healthchecks.minio.MinioHealthProperties;

public class SentinelCheckValidator implements ConstraintValidator<ValidSentinelCheck, MinioHealthProperties.SentinelCheck> {

    @Override
    public boolean isValid(MinioHealthProperties.SentinelCheck value, ConstraintValidatorContext ctx) {
        if (value == null || !value.isEnabled()) {
            return true;
        }

        return value.getSentinelBucket() != null && !value.getSentinelBucket().isBlank();
    }
}
