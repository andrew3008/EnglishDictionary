package space.br1440.platform.tracing.test.arch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import space.br1440.platform.tracing.io.opentelemetry.fake.FakeOtelType;

import io.opentelemetry.api.trace.Span;

class OtelDirectIntegrationRulesTest {

    @Test
    void detectsLocalCopyOfOtelSdkClassName() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(
                space.br1440.platform.tracing.violations.BatchSpanProcessor.class);

        // when
        // then
        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES.check(classes))
                .hasMessageContaining("BatchSpanProcessor");
    }

    @Test
    void allowsPlatformSpanProcessorImplementation() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodPlatformProcessor.class);

        // when
        // then
        assertThatCode(() -> OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES.check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void detectsLocalOtelContractName() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(
                space.br1440.platform.tracing.violations.SpanProcessor.class);

        // when
        // then
        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES.check(classes))
                .hasMessageContaining("SpanProcessor");
    }

    @Test
    void detectsFakeOtelSubpackage() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(FakeOtelType.class);

        // when
        // then
        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES.check(classes))
                .hasMessageContaining("FakeOtelType");
    }

    @Test
    void detectsRawRecordExceptionCall() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(RawRecordExceptionViolation.class);

        // when
        // then
        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER.check(classes))
                .hasMessageContaining("recordException");
    }

    /** Корректный паттерн: platform-имя без коллизии с OTel SDK. */
    @SuppressWarnings("unused")
    static class GoodPlatformProcessor {
        void onStart() {
        }
    }

    static final class RawRecordExceptionViolation {

        void record(Throwable failure) {
            Span.current().recordException(failure);
        }
    }
}
