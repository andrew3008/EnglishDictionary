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
        String httpProvider = ObservingHttpSenderProvider.class.getName();
        String grpcProvider = ObservingGrpcSenderProvider.class.getName();
        String configuredHttp = effectivePreference(HTTP_PREFERENCE_KEY);
        String configuredGrpc = effectivePreference(GRPC_PREFERENCE_KEY);

        requireCompatible(HTTP_PREFERENCE_KEY, httpProvider, configuredHttp);
        requireCompatible(GRPC_PREFERENCE_KEY, grpcProvider, configuredGrpc);

        installIfMissing(HTTP_PREFERENCE_KEY, httpProvider, configuredHttp);
        installIfMissing(GRPC_PREFERENCE_KEY, grpcProvider, configuredGrpc);
    }

    private static void requireCompatible(String preferenceKey, String providerClassName, String configured) {
        if (configured.isEmpty()) {
            return;
        }

        if (configured.equals(providerClassName)) {
            return;
        }

        String message = "Controlled agent requires %s to be %s, but it is configured as %s"
                .formatted(preferenceKey, providerClassName, configured);
        throw new IllegalStateException(message);
    }

    private static void installIfMissing(String preferenceKey, String providerClassName, String configured) {
        if (configured.isEmpty()) {
            System.setProperty(preferenceKey, providerClassName);
        }
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
