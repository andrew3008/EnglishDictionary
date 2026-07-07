package space.br1440.platform.tracing.api.control.protocol;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolSchema;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator;
import space.br1440.platform.tracing.api.control.protocol.version.TracingControlProtocolVersion;

import java.util.Map;
import java.util.Optional;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public final class TracingControlProtocol {

    private final TracingControlProtocolVersion version;
    private final TracingControlProtocolSchema schema;
    private final TracingControlProtocolValidator validator;

    public static TracingControlProtocol current() {
        return Registry.CURRENT;
    }

    public static Optional<TracingControlProtocol> find(TracingControlProtocolVersion version) {
        return Optional.ofNullable(Registry.BY_MAJOR.get(version.major()));
    }

    public static boolean isSupported(TracingControlProtocolVersion version) {
        return Registry.BY_MAJOR.containsKey(version.major());
    }

    public static TracingControlProtocolVersion minSupportedVersion() {
        return Registry.MIN_VERSION;
    }

    public static TracingControlProtocolVersion maxSupportedVersion() {
        return Registry.MAX_VERSION;
    }

    @UtilityClass
    private static final class Registry {

        private static final TracingControlProtocolVersion V1 = new TracingControlProtocolVersion(1);

        private static final TracingControlProtocolSchema SCHEMA_V1 = TracingControlProtocolSchema.forMajor(1);

        private static final TracingControlProtocol CURRENT = new TracingControlProtocol(
                V1,
                SCHEMA_V1,
                new TracingControlProtocolValidator(SCHEMA_V1)
        );

        private static final Map<Integer, TracingControlProtocol> BY_MAJOR = Map.of(1, CURRENT);

        private static final TracingControlProtocolVersion MIN_VERSION = V1;
        private static final TracingControlProtocolVersion MAX_VERSION = V1;
    }
}
