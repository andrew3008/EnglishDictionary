package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ObservingSenderPreference {

    static final String HTTP_PREFERENCE_KEY = "io.opentelemetry.sdk.common.export.HttpSenderProvider";
    static final String GRPC_PREFERENCE_KEY = "io.opentelemetry.sdk.common.export.GrpcSenderProvider";

    public static void install() {
        install(HTTP_PREFERENCE_KEY, ObservingHttpSenderProvider.class.getName());
        install(GRPC_PREFERENCE_KEY, ObservingGrpcSenderProvider.class.getName());
    }

    private static void install(String preferenceKey, String providerClassName) {
        String configured = effectivePreference(preferenceKey);
        if (configured.isEmpty()) {
            System.setProperty(preferenceKey, providerClassName);
            return;
        }

        if (configured.equals(providerClassName)) {
            return;
        }

        String message = "Controlled agent requires %s to be %s, but it is configured as %s"
                .formatted(preferenceKey, providerClassName, configured);
        throw new IllegalStateException(message);
    }

    private static String effectivePreference(String preferenceKey) {
        String normalizedKey = normalizeProperty(preferenceKey);

        Properties properties = (Properties) System.getProperties().clone();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            if (normalizedKey.equals(normalizeProperty(entry.getKey().toString()))) {
                return entry.getValue().toString();
            }
        }

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (normalizedKey.equals(normalizeEnvironmentVariable(entry.getKey()))) {
                return entry.getValue();
            }
        }

        return "";
    }

    private static String normalizeProperty(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", ".");
    }

    private static String normalizeEnvironmentVariable(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", ".");
    }
}
