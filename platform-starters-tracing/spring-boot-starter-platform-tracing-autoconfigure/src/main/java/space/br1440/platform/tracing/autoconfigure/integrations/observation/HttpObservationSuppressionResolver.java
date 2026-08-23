package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;

import lombok.experimental.UtilityClass;

/**
 * Эффективное значение подавления Micrometer HTTP observations: явный property либо
 * auto-derive от присутствия Agent.
 * <p>
 * Подавление гасит {@code http.server.requests} / {@code http.client.requests}. Оно не создаёт
 * второй OTel-продюсер и не отменяет Agent spans.
 */
@UtilityClass
public final class HttpObservationSuppressionResolver {

    public static final String PROPERTY_NAME =
            TracingProperties.PREFIX + ".suppression.suppress-micrometer-http-observations";

    public static boolean resolve(Environment environment) {
        Boolean explicit = environment.getProperty(PROPERTY_NAME, Boolean.class);
        return (explicit != null) ? explicit : OtelJavaAgentMarkerDetector.isAgentPresent();
    }

    public static boolean isExplicitlySet(Environment environment) {
        return (environment.getProperty(PROPERTY_NAME, Boolean.class) != null);
    }
}
