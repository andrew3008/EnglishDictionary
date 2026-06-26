package space.br1440.platform.datasource.support.assertj;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.boot.actuate.health.Health;
import space.br1440.platform.datasource.support.junit.CapturedLogs;

import java.util.List;

/**
 * Единая точка входа для fluent AssertJ-проверок данной библиотеки.
 *
 * <pre>{@code
 * RoutingAssertions.assertThat(jdbcUrl)
 *     .hasTargetServerType("any")
 *     .hasParam("loadBalanceHosts", "true")
 *     .hasNoParam("sslmode");
 *
 * RoutingAssertions.assertThat(meterRegistry)
 *     .hasCounterValue("datasource.routing.requests", "target", "read", 3.0);
 *
 * RoutingAssertions.assertThat(observationRegistry)
 *     .hasObservationWithName("datasource.routing")
 *         .thatHasLowCardinalityKeyValue("db.routing.target", "read");
 * }</pre>
 */
public final class RoutingAssertions {

    private RoutingAssertions() {
    }

    public static HikariUrlAssert assertThat(String jdbcUrl) {
        return new HikariUrlAssert(jdbcUrl);
    }

    public static HikariDataSourceAssert assertThat(HikariDataSource dataSource) {
        return new HikariDataSourceAssert(dataSource);
    }

    public static MetricsAssert assertThat(MeterRegistry registry) {
        return new MetricsAssert(registry);
    }

    public static ObservationAssert assertThat(TestObservationRegistry registry) {
        return new ObservationAssert(registry);
    }

    public static HealthAssert assertThat(Health health) {
        return new HealthAssert(health);
    }

    public static LogEventsAssert assertThat(CapturedLogs logs) {
        return new LogEventsAssert(logs.events());
    }

    public static LogEventsAssert assertThat(List<ILoggingEvent> events) {
        return new LogEventsAssert(events);
    }
}
