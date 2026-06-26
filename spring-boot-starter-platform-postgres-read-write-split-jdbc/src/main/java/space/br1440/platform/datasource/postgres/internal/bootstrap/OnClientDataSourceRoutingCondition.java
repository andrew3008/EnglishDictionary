package space.br1440.platform.datasource.postgres.internal.bootstrap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;

/**
 * <p>Стартер активируется тогда и только тогда, когда <b>canonical</b> resolved property
 * {@value #PROPERTY} == CLIENT.
 *
 * <p>Отсутствие значения или {@code PROXY} → noMatch → стартер не создаёт бинов, приложение
 * идёт по стандартной Spring Boot {@code DataSourceAutoConfiguration}.
 */
public final class OnClientDataSourceRoutingCondition extends SpringBootCondition {

    public static final String PROPERTY = PgClusterProperties.PREFIX + ".datasource-routing-mode";

    private static final String CONDITION_NAME = "OnClientDataSourceRouting";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(PROPERTY);

        if (StringUtils.isBlank(value)) {
            return ConditionOutcome.noMatch(
                    ConditionMessage.forCondition(CONDITION_NAME).notAvailable(PROPERTY));
        }

        if (DataSourceRoutingMode.CLIENT.name().equalsIgnoreCase(value.strip())) {
            return ConditionOutcome.match(
                    ConditionMessage.forCondition(CONDITION_NAME)
                            .found(PROPERTY).items(DataSourceRoutingMode.CLIENT.name()));
        }

        return ConditionOutcome.noMatch(
                ConditionMessage.forCondition(CONDITION_NAME)
                        .because(PROPERTY + "=" + value + " (expected " + DataSourceRoutingMode.CLIENT.name() + ")"));
    }
}
