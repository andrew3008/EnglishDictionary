package space.br1440.platform.tracing.autoconfigure.integrations.observation;

import org.springframework.core.env.Environment;

import space.br1440.platform.tracing.autoconfigure.agent.OtelJavaAgentMarkerDetector;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapPropertyNames;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class HttpObservationSuppressionResolver {

    public static final String PROPERTY_NAME = TracingBootstrapPropertyNames.SUPPRESS_MICROMETER_HTTP_OBSERVATIONS;

    public static boolean resolve(Environment environment) {
        Boolean explicit = environment.getProperty(PROPERTY_NAME, Boolean.class);
        return (explicit != null) ? explicit : OtelJavaAgentMarkerDetector.isAgentPresent();
    }

    public static boolean isExplicitlySet(Environment environment) {
        return (environment.getProperty(PROPERTY_NAME, Boolean.class) != null);
    }
}
