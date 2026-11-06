package space.br1440.platform.tracing.api.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformBaggageKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {"platform.x", "PLATFORM.x", "Platform.Correlation.Id", "platform.correlation.id"})
    void reserves_the_whole_prefix_regardless_of_case(String key) {
        // when/then
        assertThat(PlatformBaggageKeys.isReserved(key)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"platform", "platformx.y", "xplatform.y"})
    void does_not_reserve_keys_outside_the_prefix(String key) {
        // when/then
        assertThat(PlatformBaggageKeys.isReserved(key)).isFalse();
    }

    @Test
    void correlation_id_uses_the_reserved_prefix() {
        // when/then
        assertThat(PlatformBaggageKeys.CORRELATION_ID).startsWith(PlatformBaggageKeys.RESERVED_PREFIX);
        assertThat(PlatformBaggageKeys.isReserved(PlatformBaggageKeys.CORRELATION_ID)).isTrue();
    }
}
