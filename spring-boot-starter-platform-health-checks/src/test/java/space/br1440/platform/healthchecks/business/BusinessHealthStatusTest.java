package space.br1440.platform.healthchecks.business;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.common.HealthStatuses;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BusinessHealthStatus")
class BusinessHealthStatusTest {

    @Nested
    @DisplayName("factory methods")
    class Factory {

        @Test
        @DisplayName("up() — status UP, message null, details пустой")
        void up_createsStatusWithUpAndNoMessage() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // Then
            assertThat(status.getStatus()).isEqualTo(Status.UP);
            assertThat(status.getMessage()).isNull();
            assertThat(status.getDetails()).isEmpty();
            assertThat(status.getException()).isNull();
            assertThat(status.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("up(message) — message задан")
        void up_withMessage_createsStatusWithMessage() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.up("All clear");

            // Then
            assertThat(status.getStatus()).isEqualTo(Status.UP);
            assertThat(status.getMessage()).isEqualTo("All clear");
        }

        @Test
        @DisplayName("down(message) — status DOWN, message задан")
        void down_withMessage_createsStatusDown() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.down("DB down");

            // Then
            assertThat(status.getStatus()).isEqualTo(Status.DOWN);
            assertThat(status.getMessage()).isEqualTo("DB down");
        }

        @Test
        @DisplayName("degraded(message) — status DEGRADED, message задан")
        void degraded_withMessage_createsStatusDegraded() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.degraded("Partner API slow");

            // Then
            assertThat(status.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
            assertThat(status.getMessage()).isEqualTo("Partner API slow");
        }
    }

    @Nested
    @DisplayName("builder")
    class Builder {

        @Test
        @DisplayName("detail(null, value) — NPE с сообщением про key")
        void builder_detailNullKey_throws() {
            // When / Then
            assertThatThrownBy(() ->
                    BusinessHealthStatus.builder()
                            .up()
                            .detail(null, "value")
                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("key");
        }

        @Test
        @DisplayName("details(Map) — все записи попадают в getDetail()")
        void builder_detailsBulk_mergesEntries() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .up()
                    .details(Map.of("a", 1, "b", 2))
                    .build();

            // Then
            assertThat(status.getDetail("a")).isEqualTo(1);
            assertThat(status.getDetail("b")).isEqualTo(2);
        }

        @Test
        @DisplayName("details(null) — не NPE, details остаётся пустым")
        void builder_detailsNull_ignored() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .up()
                    .details(null)
                    .build();

            // Then
            assertThat(status.getDetails()).isEmpty();
        }

        @Test
        @DisplayName("build() без timestamp — getTimestamp() не null")
        void builder_noTimestamp_usesNow() {
            // When
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .up()
                    .build();

            // Then
            assertThat(status.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("build() без status — NPE при build()")
        void builder_noStatus_throws() {
            // When / Then
            assertThatThrownBy(() ->
                    BusinessHealthStatus.builder()
                            .message("x")
                            .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Status");
        }
    }

    @Nested
    @DisplayName("immutability and details accessors")
    class Immutability {

        @Test
        @DisplayName("getDetails() возвращает неизменяемую Map")
        void getDetails_returnsImmutableMap() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .up()
                    .detail("k", "v")
                    .build();

            // When / Then
            assertThatThrownBy(() -> status.getDetails().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("hasDetails() true когда details не пустой")
        void hasDetails_trueWhenNotEmpty() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .up()
                    .detail("k", "v")
                    .build();

            // Then
            assertThat(status.hasDetails()).isTrue();
        }

        @Test
        @DisplayName("hasDetails() false когда details пустой")
        void hasDetails_falseWhenEmpty() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // Then
            assertThat(status.hasDetails()).isFalse();
        }

        @Test
        @DisplayName("getDetail(absent) возвращает null")
        void getDetail_missingKey_returnsNull() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // When / Then
            assertThat(status.getDetail("absent")).isNull();
        }
    }

    @Nested
    @DisplayName("exception")
    class Exception {

        @Test
        @DisplayName("builder.exception() — hasException() true, getException() возвращает тот же экземпляр")
        void builder_exception_stored() {
            // Given
            RuntimeException ex = new RuntimeException("e");

            // When
            BusinessHealthStatus status = BusinessHealthStatus.builder()
                    .down()
                    .exception(ex)
                    .build();

            // Then
            assertThat(status.hasException()).isTrue();
            assertThat(status.getException()).isSameAs(ex);
            assertThat(status.getException().getMessage()).isEqualTo("e");
        }

        @Test
        @DisplayName("без exception — hasException() false")
        void noException_hasExceptionFalse() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.down("reason");

            // Then
            assertThat(status.hasException()).isFalse();
            assertThat(status.getException()).isNull();
        }
    }

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("equals рефлексивен")
        void equals_reflexive() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // Then
            assertThat(status).isEqualTo(status);
            assertThat(status.hashCode()).isEqualTo(status.hashCode());
        }

        @Test
        @DisplayName("разный status — не equal")
        void differentStatus_notEqual() {
            // Given
            BusinessHealthStatus up = BusinessHealthStatus.up();
            BusinessHealthStatus down = BusinessHealthStatus.down("r");

            // Then
            assertThat(up).isNotEqualTo(down);
            assertThat(up.hashCode()).isNotEqualTo(down.hashCode());
        }

        @Test
        @DisplayName("разный message — не equal")
        void differentMessage_notEqual() {
            // Given
            BusinessHealthStatus a = BusinessHealthStatus.up("a");
            BusinessHealthStatus b = BusinessHealthStatus.up("b");

            // Then
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("null — не equal")
        void equals_null_notEqual() {
            // Given
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // Then
            assertThat(status).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("isUp()")
    class IsUp {

        @Test
        @DisplayName("true когда status == UP")
        void trueWhenStatusUp() {
            // Given / When
            BusinessHealthStatus status = BusinessHealthStatus.up();

            // Then
            assertThat(status.isUp()).isTrue();
        }

        @Test
        @DisplayName("false когда status DOWN или DEGRADED")
        void falseWhenStatusDownOrDegraded() {
            // Given / When
            BusinessHealthStatus down = BusinessHealthStatus.down("x");
            BusinessHealthStatus degraded = BusinessHealthStatus.degraded("x");

            // Then
            assertThat(down.isUp()).isFalse();
            assertThat(degraded.isUp()).isFalse();
        }
    }
}
