package space.br1440.platform.datasource.configuration.condition;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Условие активации auto-configuration стартеров PostgreSQL-кластера.
 *
 * <p>Стартеры активируются тогда и только тогда, когда environment-переменная
 * {@value #ENV_VAR} содержит значение {@value #DIRECT} (case-insensitive по значению).
 * Любое другое значение и отсутствие переменной → условие не сработало, стартер не
 * создаёт ни одного бина, и микросервис идёт по канонической Spring Boot
 * {@code DataSourceAutoConfiguration} через {@code spring.datasource.url=${POSTGRES_URL}}.
 */
public final class OnPgTopologyDirectCondition extends SpringBootCondition {

    public static final String ENV_VAR = "PG_TOPOLOGY";
    public static final String DIRECT = "DIRECT";

    private static final String CONDITION_NAME = "OnPgTopologyDirect";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(ENV_VAR);

        if (value == null) {
            return ConditionOutcome.noMatch(
                    ConditionMessage.forCondition(CONDITION_NAME)
                            .notAvailable(ENV_VAR + " env var")
            );
        }

        if (DIRECT.equalsIgnoreCase(value)) {
            return ConditionOutcome.match(
                    ConditionMessage.forCondition(CONDITION_NAME)
                            .found(ENV_VAR)
                            .items(DIRECT)
            );
        }

        return ConditionOutcome.noMatch(
                ConditionMessage.forCondition(CONDITION_NAME)
                        .because(ENV_VAR + "=" + value + " (expected " + DIRECT + ")")
        );
    }
}
