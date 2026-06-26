package space.br1440.platform.errorhandling.mvc.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MicrometerExceptionMetricsRecorder")
class MicrometerExceptionMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private MicrometerExceptionMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new MicrometerExceptionMetricsRecorder(registry);
    }

    @Test
    @DisplayName("Каждый вызов инкрементирует counter с тегами {error_code, http_status, domain}")
    void increments_counter_withFixedCardinalityTags() {
        // given
        ErrorEntry entry = ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errorCode("INVALID_ARGUMENT")
                .message("bad request")
                .domain("test-service")
                .build();

        // when
        recorder.onException(new RuntimeException("x"), entry, "corr-1");
        recorder.onException(new RuntimeException("x"), entry, "corr-2");

        // then
        Counter counter = registry.find(MicrometerExceptionMetricsRecorder.METRIC_NAME)
                .tag("error_code", "INVALID_ARGUMENT")
                .tag("http_status", "400")
                .tag("domain", "test-service")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Если domain пустой — используется маркер 'unknown' (не пустая метка)")
    void emptyDomain_marksAsUnknown() {
        // given
        ErrorEntry entry = ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("INTERNAL")
                .message("boom")
                .build();

        // when
        recorder.onException(new RuntimeException("x"), entry, "corr");

        // then
        Counter counter = registry.find(MicrometerExceptionMetricsRecorder.METRIC_NAME)
                .tag("domain", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("correlationId НЕ попадает в теги (защита от unbounded cardinality)")
    void correlationId_notInTags() {
        // given
        ErrorEntry entry = ErrorEntry.builder()
                .httpStatus(HttpStatus.NOT_FOUND)
                .errorCode("NOT_FOUND")
                .message("not found")
                .domain("svc")
                .build();

        // when — два разных correlation-id
        recorder.onException(new RuntimeException("x"), entry, "corr-A");
        recorder.onException(new RuntimeException("x"), entry, "corr-B");

        // then — обе записи попали в один counter (значит correlationId не разделил их)
        Counter counter = registry.find(MicrometerExceptionMetricsRecorder.METRIC_NAME).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }
}
