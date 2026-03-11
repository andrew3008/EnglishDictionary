package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class BucketNameValidator implements ConstraintValidator<ValidBucketName, String> {

    /**
     * see: <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html">...</a>
     */
    private static final java.util.regex.Pattern BUCKET_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9\\-.]{1,61}[a-z0-9]$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null-проверка — зона ответственности @NotNull/@NotBlank
        }

        if (!BUCKET_NAME_PATTERN.matcher(value).matches()) {
            return false;
        }

        // Дополнительное правило: запрет двух точек подряд — нельзя выразить в одном regex чисто
        if (value.contains("..")) {
            return false;
        }

        // IP-адреса запрещены как имена бакетов в AWS S3 (192.168.1.1 и подобные)
        if (value.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            return false;
        }

        return true;
    }
}
