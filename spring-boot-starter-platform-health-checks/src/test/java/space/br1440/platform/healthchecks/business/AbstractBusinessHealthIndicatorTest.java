package space.br1440.platform.healthchecks.business;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.HealthStatuses;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AbstractBusinessHealthIndicator")
class AbstractBusinessHealthIndicatorTest {

    private static final String CHECK_NAME = "payment-gateway";

    private static AbstractBusinessHealthIndicator indicator(BusinessHealthStatus result) {
        return new AbstractBusinessHealthIndicator() {
            @Override
            protected String getHealthCheckName() {
                return CHECK_NAME;
            }

            @Override
            protected BusinessHealthStatus executeHealthCheck() {
                return result;
            }
        };
    }

    private static AbstractBusinessHealthIndicator throwingIndicator(Status failureStatus, Exception exception) {
        return new AbstractBusinessHealthIndicator() {
            @Override
            protected String getHealthCheckName() {
                return CHECK_NAME;
            }

            @Override
            protected Status getFailureHealthCheckStatus() {
                return failureStatus;
            }

            @Override
            protected BusinessHealthStatus executeHealthCheck() {
                if (exception instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(exception);
            }
        };
    }

    @Nested
    @DisplayName("executeHealthCheck → Status propagation")
    class StatusPropagation {

        @Test
        @DisplayName("UP когда executeHealthCheck возвращает up()")
        void returnsUpWhenResultIsUp() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up());

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("UP с сообщением включается в details")
        void upWithMessageIncludedInDetails() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up("All systems operational"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("message", "All systems operational");
        }

        @Test
        @DisplayName("DOWN когда executeHealthCheck возвращает down(message)")
        void returnsDownWhenResultIsDown() {
            // Given
            var indicator = indicator(BusinessHealthStatus.down("Database unavailable"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("message", "Database unavailable");
        }

        @Test
        @DisplayName("DEGRADED когда executeHealthCheck возвращает degraded(message)")
        void returnsDegradedWhenResultIsDegraded() {
            // Given
            var indicator = indicator(BusinessHealthStatus.degraded("Partner API slow"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
            assertThat(health.getDetails()).containsEntry("message", "Partner API slow");
        }

        @Test
        @DisplayName("message отсутствует в details когда не задан")
        void omitsMessageWhenNull() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up());

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getDetails()).doesNotContainKey("message");
        }
    }

    @Nested
    @DisplayName("alertRequired")
    class AlertRequired {

        @Test
        @DisplayName("отсутствует когда статус UP")
        void absentWhenUp() {
            var indicator = indicator(BusinessHealthStatus.up());

            Health health = indicator.health();

            assertThat(health.getDetails()).doesNotContainKey("alertRequired");
        }

        @Test
        @DisplayName("присутствует когда статус DOWN")
        void presentWhenDown() {
            var indicator = indicator(BusinessHealthStatus.down("Failure"));

            Health health = indicator.health();

            assertThat(health.getDetails()).containsEntry("alertRequired", true);
        }

        @Test
        @DisplayName("присутствует когда статус DEGRADED")
        void presentWhenDegraded() {
            var indicator = indicator(BusinessHealthStatus.degraded("Degraded"));

            Health health = indicator.health();

            assertThat(health.getDetails()).containsEntry("alertRequired", true);
        }

        @Test
        @DisplayName("отсутствует при UP после исключения (informational: getFailureHealthCheckStatus() = UP)")
        void absentWhenUpAfterException() {
            var indicator = throwingIndicator(Status.UP, new RuntimeException("non-critical"));

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).doesNotContainKey("alertRequired");
        }
    }

    @Nested
    @DisplayName("buildHealth — mandatory details")
    class MandatoryDetails {

        @Test
        @DisplayName("содержит healthCheckName и timestamp")
        void includesHealthCheckNameAndTimestamp() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up());

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("healthCheckName", CHECK_NAME)
                    .containsKey("timestamp");
        }

        @Test
        @DisplayName("timestamp в формате ISO-8601")
        void timestampIsIso8601() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up());

            // When
            String timestamp = (String) indicator.health().getDetails().get("timestamp");

            // Then
            assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
        }

        @Test
        @DisplayName("пользовательские details попадают в Health")
        void customDetailsPassedThrough() {
            // Given
            var status = BusinessHealthStatus.builder()
                    .up()
                    .detail("responseTimeMs", 42)
                    .detail("region", "eu-west-1")
                    .build();
            var indicator = indicator(status);

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("responseTimeMs", 42)
                    .containsEntry("region", "eu-west-1");
        }

        @Test
        @DisplayName("healthCheckName не перезаписывается пользовательским detail")
        void healthCheckNameNotOverriddenByCustomDetail() {
            // Given
            var status = BusinessHealthStatus.builder()
                    .up()
                    .detail("healthCheckName", "custom-name")
                    .build();
            var indicator = indicator(status);

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details).containsEntry("healthCheckName", CHECK_NAME);
        }

        @Test
        @DisplayName("timestamp из статуса перезаписывает пользовательский detail")
        void timestampFromStatusOverridesCustomDetail() {
            // Given
            var status = BusinessHealthStatus.builder()
                    .up()
                    .detail("timestamp", "FAKE_VALUE")
                    .build();
            var indicator = indicator(status);

            // When
            String timestamp = (String) indicator.health().getDetails().get("timestamp");

            // Then
            assertThat(timestamp)
                    .isNotEqualTo("FAKE_VALUE")
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
        }

        @Test
        @DisplayName("bulk details через Map передаются в Health")
        void bulkDetailsPassedThrough() {
            // Given
            var status = BusinessHealthStatus.builder()
                    .up()
                    .details(Map.of("key1", "v1", "key2", 99))
                    .build();
            var indicator = indicator(status);

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("key1", "v1")
                    .containsEntry("key2", 99);
        }
    }

    @Nested
    @DisplayName("buildHealth — exception в возвращённом BusinessHealthStatus")
    class BuildHealthExceptionInStatus {

        @Test
        @DisplayName("при status.hasException() == true в details попадают errorType и error")
        void includesErrorTypeAndErrorWhenStatusHasException() {
            // Given
            var exception = new RuntimeException("connection refused");
            var status = BusinessHealthStatus.builder()
                    .down()
                    .message("DB error")
                    .exception(exception)
                    .build();
            var indicator = indicator(status);

            // When
            Health health = indicator.health();
            Map<String, Object> details = health.getDetails();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(details)
                    .containsEntry("errorType", "RuntimeException")
                    .containsEntry("error", "connection refused")
                    .containsEntry("message", "DB error");
        }

        @Test
        @DisplayName("при exception.getMessage() == null ключ error в details отсутствует")
        void omitsErrorDetailWhenExceptionMessageNull() {
            // Given
            var status = BusinessHealthStatus.builder()
                    .degraded()
                    .message("Degraded")
                    .exception(new NullPointerException())
                    .build();
            var indicator = indicator(status);

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("errorType", "NullPointerException")
                    .doesNotContainKey("error");
        }
    }

    @Nested
    @DisplayName("exception handling — onError и getFailureHealthCheckStatus")
    class ExceptionHandling {

        @Test
        @DisplayName("по умолчанию getFailureHealthCheckStatus() = DOWN → onError возвращает DOWN")
        void defaultFailureStatusIsDown() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new IllegalStateException("Connection refused"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("onError добавляет errorType и error в details")
        void onErrorIncludesErrorDetails() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new IllegalStateException("Pool exhausted"));

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("errorType", "IllegalStateException")
                    .containsEntry("error", "Pool exhausted");
        }

        @Test
        @DisplayName("getFailureHealthCheckStatus() = DEGRADED → onError возвращает DEGRADED (optional check)")
        void optionalCheckReturnsDegradedOnException() {
            // Given
            var indicator = throwingIndicator(HealthStatuses.DEGRADED, new RuntimeException("API error"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        }

        @Test
        @DisplayName("getFailureHealthCheckStatus() = UP → onError возвращает UP (informational)")
        void informationalCheckReturnsUpOnException() {
            // Given
            var indicator = throwingIndicator(Status.UP, new RuntimeException("non-critical"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("error отсутствует когда exception.getMessage() == null")
        void omitsErrorDetailWhenMessageNull() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new NullPointerException());

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details)
                    .containsEntry("errorType", "NullPointerException")
                    .doesNotContainKey("error");
        }

        @Test
        @DisplayName("message = 'Health check failed' при пути через onError()")
        void defaultMessageFromOnError() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new RuntimeException("failure"));

            // When
            Map<String, Object> details = indicator.health().getDetails();

            // Then
            assertThat(details).containsEntry("message", "Health check failed");
        }

        @Test
        @DisplayName("checked exception оборачивается в RuntimeException → DOWN и cause в error")
        void checkedExceptionWrappedThenDown() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new IOException("timeout"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("errorType", "RuntimeException")
                    .containsEntry("error", "java.io.IOException: timeout");
        }
    }

    @Nested
    @DisplayName("onError() — переопределение подклассом")
    class OnErrorOverride {

        @Test
        @DisplayName("подкласс возвращает up('recovered') → UP без errorType в details")
        void customOnErrorReturnsUp() {
            // Given
            var indicator = new AbstractBusinessHealthIndicator() {
                @Override
                protected String getHealthCheckName() {
                    return CHECK_NAME;
                }

                @Override
                protected BusinessHealthStatus executeHealthCheck() {
                    throw new RuntimeException("transient failure");
                }

                @Override
                protected BusinessHealthStatus onError(Exception e) {
                    return BusinessHealthStatus.up("recovered");
                }
            };

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("message", "recovered")
                    .doesNotContainKey("errorType");
        }
    }

    @Nested
    @DisplayName("getProbes")
    class Probes {

        @Test
        @DisplayName("по умолчанию READINESS")
        void defaultIsReadiness() {
            // Given
            var indicator = indicator(BusinessHealthStatus.up());

            // Then
            assertThat(indicator.getProbes()).containsExactly(HealthProbe.READINESS);
        }
    }

    @Nested
    @DisplayName("observability — логирование")
    class Logging {

        @Test
        @DisplayName("WARN с именем проверки при исключении в executeHealthCheck()")
        void logsWarnWithCheckNameOnException() {
            // Given
            var indicator = throwingIndicator(Status.DOWN, new RuntimeException("connection timeout"));
            Logger logger = (Logger) LoggerFactory.getLogger(AbstractBusinessHealthIndicator.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                // When
                indicator.health();

                // Then
                assertThat(appender.list)
                        .anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains(CHECK_NAME);
                        });
            } finally {
                logger.detachAppender(appender);
            }
        }
    }
}
