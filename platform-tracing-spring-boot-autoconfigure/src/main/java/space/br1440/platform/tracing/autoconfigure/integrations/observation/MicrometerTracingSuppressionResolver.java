package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.agent.OtelAgentDetector;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class MicrometerTracingSuppressionResolver {

    public static final String PROPERTY_NAME = TracingProperties.PREFIX + ".suppression.suppress-micrometer-tracing";

    public static boolean resolve(Environment environment) {
        Boolean explicit = environment.getProperty(PROPERTY_NAME, Boolean.class);
        return (explicit != null) ? explicit : OtelAgentDetector.isAgentPresent();
    }

    public static boolean isExplicitlySet(Environment environment) {
        return (environment.getProperty(PROPERTY_NAME, Boolean.class) != null);
    }
}
