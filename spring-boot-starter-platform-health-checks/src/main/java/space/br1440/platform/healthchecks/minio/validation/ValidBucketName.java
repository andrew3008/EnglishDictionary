package space.br1440.platform.healthchecks.minio.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = BucketNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidBucketName {

    /**
     * see: <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html">...</a>
     */
    String message() default "Invalid bucket name: must be 3–63 chars, lowercase letters, numbers, hyphens or dots, start and end with a letter or number, no consecutive dots";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
