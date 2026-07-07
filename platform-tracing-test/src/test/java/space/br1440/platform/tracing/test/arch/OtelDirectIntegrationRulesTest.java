package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-test для {@link OtelDirectIntegrationRules}: синтетические нарушители ловятся,
 * корректные классы проходят.
 */
class OtelDirectIntegrationRulesTest {

    @Test
    void detectsLocalCopyOfOtelSdkClassName() {
        JavaClasses classes = new ClassFileImporter().importClasses(
                space.br1440.platform.tracing.violations.BatchSpanProcessor.class);

        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES.check(classes))
                .hasMessageContaining("BatchSpanProcessor");
    }

    @Test
    void allowsPlatformSpanProcessorImplementation() {
        JavaClasses classes = new ClassFileImporter().importClasses(GoodPlatformProcessor.class);

        assertThatCode(() -> OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES.check(classes))
                .doesNotThrowAnyException();
    }

    @Test
    void detectsLocalOtelContractName() {
        JavaClasses classes = new ClassFileImporter().importClasses(
                space.br1440.platform.tracing.violations.SpanProcessor.class);

        assertThatThrownBy(() -> OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES.check(classes))
                .hasMessageContaining("SpanProcessor");
    }

    /** Корректный паттерн: platform-имя без коллизии с OTel SDK. */
    @SuppressWarnings("unused")
    static class GoodPlatformProcessor {
        void onStart() {
        }
    }
}
