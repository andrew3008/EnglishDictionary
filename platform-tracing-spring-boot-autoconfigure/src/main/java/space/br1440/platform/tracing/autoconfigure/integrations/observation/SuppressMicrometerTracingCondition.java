package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Активирует Micrometer HTTP-observation suppression без обязательной явной настройки:
 * {@code platform.tracing.suppression.suppress-micrometer-tracing} auto-derive'ится из
 * присутствия OpenTelemetry Java Agent, если оператор не задал свойство явно.
 * <p>
 * Логика вынесена из {@code @ConditionalOnProperty}, потому что у неё нет static default —
 * default зависит от runtime-состояния JVM (см. {@link MicrometerTracingSuppressionResolver}).
 */
public final class SuppressMicrometerTracingCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean explicit = MicrometerTracingSuppressionResolver.isExplicitlySet(environment);
        boolean effective = MicrometerTracingSuppressionResolver.resolve(environment);

        ConditionMessage.Builder message = ConditionMessage.forCondition("SuppressMicrometerTracing");
        if (explicit) {
            return new ConditionOutcome(effective, message.because(
                    MicrometerTracingSuppressionResolver.PROPERTY_NAME + "=" + effective + " (explicit)"));
        }
        return new ConditionOutcome(effective, message.because(
                "not set explicitly; auto-derived from OpenTelemetry Java Agent presence=" + effective));
    }
}
