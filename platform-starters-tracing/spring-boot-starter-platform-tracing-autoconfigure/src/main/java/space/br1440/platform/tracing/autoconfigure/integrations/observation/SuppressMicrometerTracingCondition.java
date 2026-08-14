package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class SuppressMicrometerTracingCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean explicit = MicrometerTracingSuppressionResolver.isExplicitlySet(environment);
        boolean effective = MicrometerTracingSuppressionResolver.resolve(environment);

        ConditionMessage.Builder message = ConditionMessage.forCondition("SuppressMicrometerTracing");
        if (explicit) {
            ConditionMessage explicitMessage = message.because(MicrometerTracingSuppressionResolver.PROPERTY_NAME + "=" + effective + " (explicit)");
            return new ConditionOutcome(effective, explicitMessage);
        }

        ConditionMessage derivedMessage = message.because("not set explicitly; auto-derived from OpenTelemetry Java Agent presence=" + effective);
        return new ConditionOutcome(effective, derivedMessage);
    }
}
