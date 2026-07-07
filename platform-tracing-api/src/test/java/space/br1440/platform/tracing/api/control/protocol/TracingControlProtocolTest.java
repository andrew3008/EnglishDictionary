package space.br1440.platform.tracing.api.control.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.version.TracingControlProtocolVersion;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolSchema;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TracingControlProtocol")
class TracingControlProtocolTest {

    private static final TracingControlProtocolVersion VERSION_1 = new TracingControlProtocolVersion(1);
    private static final TracingControlProtocolVersion VERSION_2 = new TracingControlProtocolVersion(2);

    @Test
    @DisplayName("current() returns same instance identity on repeated calls")
    void currentReturnsSameInstance() {
        TracingControlProtocol first = TracingControlProtocol.current();
        TracingControlProtocol second = TracingControlProtocol.current();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("current().version() is version 1")
    void currentVersionIsOne() {
        assertThat(TracingControlProtocol.current().version()).isEqualTo(VERSION_1);
    }

    @Test
    @DisplayName("min and max supported version are both version 1")
    void minAndMaxSupportedVersion() {
        assertThat(TracingControlProtocol.minSupportedVersion()).isEqualTo(VERSION_1);
        assertThat(TracingControlProtocol.maxSupportedVersion()).isEqualTo(VERSION_1);
    }

    @Test
    @DisplayName("isSupported reflects registered versions")
    void isSupported() {
        assertThat(TracingControlProtocol.isSupported(VERSION_1)).isTrue();
        assertThat(TracingControlProtocol.isSupported(VERSION_2)).isFalse();
    }

    @Test
    @DisplayName("find returns present for supported version and empty for unsupported")
    void find() {
        Optional<TracingControlProtocol> foundV1 = TracingControlProtocol.find(VERSION_1);
        Optional<TracingControlProtocol> foundV2 = TracingControlProtocol.find(VERSION_2);

        assertThat(foundV1).isPresent();
        assertThat(foundV1.orElseThrow()).isSameAs(TracingControlProtocol.current());
        assertThat(foundV2).isEmpty();
    }

    @Test
    @DisplayName("current().schema() and validator() are non-null")
    void schemaAndValidatorAreNonNull() {
        TracingControlProtocol protocol = TracingControlProtocol.current();

        assertThat(protocol.schema()).isNotNull().isInstanceOf(TracingControlProtocolSchema.class);
        assertThat(protocol.validator()).isNotNull().isInstanceOf(TracingControlProtocolValidator.class);
    }
}
