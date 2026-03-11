package space.br1440.platform.healthchecks.kafka.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import space.br1440.platform.healthchecks.kafka.KafkaHealthProperties;

public class ConsumerCheckValidator implements ConstraintValidator<ValidConsumerCheck, KafkaHealthProperties.Consumer> {

    @Override
    public boolean isValid(KafkaHealthProperties.Consumer value, ConstraintValidatorContext ctx) {
        if (value == null || !value.isEnabled()) {
            return true;
        }

        return value.getGroupId() != null && !value.getGroupId().isBlank();
    }
}
