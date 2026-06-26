package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceRoutingHealthIndicatorAggregationTest {

    @ParameterizedTest(name = "{0}: writeUp={1}, readUp={2} -> {3}")
    @MethodSource("truthTable")
    void aggregateStatusTruthTable(ReadMode readMode, boolean writeUp, boolean readUp, String expected) {
        Status status = DataSourceRoutingHealthIndicator.aggregateStatus(writeUp, readUp, readMode);
        assertThat(status.getCode()).isEqualTo(expected);
    }

    static Stream<Arguments> truthTable() {
        return Stream.of(
                Arguments.of(ReadMode.STRICT_SECONDARY, true, true, "UP"),
                Arguments.of(ReadMode.STRICT_SECONDARY, true, false, "DEGRADED"),
                Arguments.of(ReadMode.STRICT_SECONDARY, false, true, "DEGRADED"),
                Arguments.of(ReadMode.STRICT_SECONDARY, false, false, "DOWN"),

                Arguments.of(ReadMode.SECONDARY_PREFERRED, true, true, "UP"),
                Arguments.of(ReadMode.SECONDARY_PREFERRED, true, false, "UP"),
                Arguments.of(ReadMode.SECONDARY_PREFERRED, false, true, "DEGRADED"),
                Arguments.of(ReadMode.SECONDARY_PREFERRED, false, false, "DOWN"),

                Arguments.of(ReadMode.ANY_NODE_LOAD_BALANCED, true, true, "UP"),
                Arguments.of(ReadMode.ANY_NODE_LOAD_BALANCED, true, false, "UP"),
                Arguments.of(ReadMode.ANY_NODE_LOAD_BALANCED, false, true, "DEGRADED"),
                Arguments.of(ReadMode.ANY_NODE_LOAD_BALANCED, false, false, "DOWN")
        );
    }
}
