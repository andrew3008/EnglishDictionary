package space.br1440.platform.tracing.e2e.support;

import java.util.List;

/**
 * Ensures child-JVM smoke processes declare the mandatory P4 authority/posture signals
 * once {@code TracingConfigurationAuthorityGuard} is wired into production auto-configuration.
 * <p>
 * Defaults are HELM + LOCAL — non-mutating, legal for every smoke that is not explicitly
 * exercising ACTUATOR or CONFIG_SERVER authority. Callers may override by already including
 * either property in the JVM {@code -D} list.
 */
final class P4AuthoritySmokeDefaults {

    private static final String AUTHORITY_KEY = "platform.tracing.configuration.authority=";
    private static final String ENVIRONMENT_KEY = "platform.environment=";

    private P4AuthoritySmokeDefaults() {
    }

    static void ensure(List<String> jvmProperties) {
        if (!containsKey(jvmProperties, AUTHORITY_KEY)) {
            jvmProperties.add(AUTHORITY_KEY + "HELM");
        }
        if (!containsKey(jvmProperties, ENVIRONMENT_KEY)) {
            // Exact key match — must not confuse with platform.tracing.service.environment.
            jvmProperties.add(ENVIRONMENT_KEY + "LOCAL");
        }
    }

    private static boolean containsKey(List<String> jvmProperties, String keyPrefix) {
        return jvmProperties.stream()
                .anyMatch(property -> property != null && property.startsWith(keyPrefix));
    }
}
