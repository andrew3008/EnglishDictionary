package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.management.ObjectName;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

/**
 * Точный allowlist observation-поверхности одного platform-owned remote connector.
 */
public final class RemoteJmxAccessProfile {

    private final Map<ObjectName, EndpointAccess> endpoints;

    private RemoteJmxAccessProfile(Map<ObjectName, MutableEndpointAccess> source) {
        Map<ObjectName, EndpointAccess> immutable = new LinkedHashMap<>();
        source.forEach((name, access) -> immutable.put(name, access.freeze()));
        this.endpoints = Collections.unmodifiableMap(immutable);
    }

    public static Builder builder() {
        return new Builder();
    }

    Set<ObjectName> objectNames() {
        return endpoints.keySet();
    }

    boolean allowsObjectName(ObjectName name) {
        return isGeneratedRemoteReadEndpoint(name) && endpoints.containsKey(name);
    }

    boolean allowsAttribute(ObjectName name, String attribute) {
        if (!isGeneratedRemoteReadAttribute(name, attribute)) {
            return false;
        }
        EndpointAccess access = endpoints.get(name);
        return access != null && access.attributes().contains(attribute);
    }

    public static final class Builder {

        private final Map<ObjectName, MutableEndpointAccess> endpoints = new LinkedHashMap<>();

        /**
         * Разрешает безопасное metadata/discovery-чтение endpoint без атрибутов и операций.
         */
        public Builder allowObservationEndpoint(ObjectName name) {
            endpoint(name);
            return this;
        }

        public Builder allowAttribute(ObjectName name, String attribute) {
            MutableEndpointAccess access = endpoint(name);
            String requiredAttribute = requiredToken(attribute, "Attribute name");
            if (!isGeneratedRemoteReadAttribute(name, requiredAttribute)) {
                throw new IllegalArgumentException(
                        "Remote JMX attribute must match generated read-only contract");
            }
            access.attributes.add(requiredAttribute);
            return this;
        }

        public RemoteJmxAccessProfile build() {
            return new RemoteJmxAccessProfile(endpoints);
        }

        private MutableEndpointAccess endpoint(ObjectName name) {
            Objects.requireNonNull(name, "Observation ObjectName must not be null");
            if (name.isPattern()) {
                throw new IllegalArgumentException("Observation ObjectName must be exact");
            }
            if (isHardDeniedMutation(name)) {
                throw new IllegalArgumentException("Remote JMX mutation endpoint must not be allowlisted");
            }
            if (!isGeneratedRemoteReadEndpoint(name)) {
                throw new IllegalArgumentException(
                        "Remote JMX endpoint must be a generated read-only endpoint");
            }
            return endpoints.computeIfAbsent(name, ignored -> new MutableEndpointAccess());
        }

        private static String requiredToken(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return value;
        }
    }

    private static final class MutableEndpointAccess {

        private final Set<String> attributes = new LinkedHashSet<>();

        private EndpointAccess freeze() {
            return new EndpointAccess(Set.copyOf(attributes));
        }
    }

    private record EndpointAccess(Set<String> attributes) {
    }

    static boolean isHardDeniedMutation(ObjectName name) {
        return GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME.equals(name);
    }

    static boolean isGeneratedRemoteReadEndpoint(ObjectName name) {
        return RemoteJmxReadGuard.isReadEndpoint(name);
    }

    static boolean isGeneratedContractEndpoint(ObjectName name) {
        return isGeneratedRemoteReadEndpoint(name) || isHardDeniedMutation(name);
    }

    static boolean isGeneratedRemoteReadAttribute(ObjectName name, String attribute) {
        return RemoteJmxReadGuard.matchesAttribute(name, attribute);
    }
}
