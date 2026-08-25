package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke-тесты для {@link OtelSdkTest}.
 *
 * <p>Покрывают контракт composite-аннотации:
 * <ul>
 *   <li>ParameterResolver инжектирует {@link OpenTelemetrySdk} и {@link InMemorySpanExporter};</li>
 *   <li>аннотация несёт {@code @Tag("tracing")};</li>
 *   <li>METHOD scope изолирован — span из одного теста не «протекает» в следующий;</li>
 *   <li>no-arg конструктор {@link OtelSdkExtension} жив (guard от cleanup-рефакторинга);</li>
 *   <li>SDK не закрывается преждевременно в {@code @Nested} (OTel upstream #7919).</li>
 * </ul>
 */
@OtelSdkTest
@DisplayName("@OtelSdkTest composite annotation")
class OtelSdkTestAnnotationTest {

    @Test
    @DisplayName("injects OpenTelemetrySdk parameter")
    void sdkIsInjected(OpenTelemetrySdk sdk) {
        assertThat(sdk).isNotNull();
    }

    @Test
    @DisplayName("injects InMemorySpanExporter parameter")
    void exporterIsInjected(InMemorySpanExporter exporter) {
        assertThat(exporter).isNotNull();
    }

    @Test
    @DisplayName("injects both parameters simultaneously")
    void bothParametersInjectedTogether(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        assertThat(sdk).isNotNull();
        assertThat(exporter).isNotNull();
    }

    @Test
    @DisplayName("@OtelSdkTest carries @Tag(\"tracing\")")
    void tracingTagIsPresent() {
        // Структурная проверка: аннотация @Tag("tracing") объявлена на типе.
        // JUnit Platform разрешает теги по тому же reflection-пути — для CI-фильтрации эквивалентно.
        boolean hasTracingTag = false;
        for (Annotation a : OtelSdkTest.class.getAnnotations()) {
            if (a instanceof Tag tag && "tracing".equals(tag.value())) {
                hasTracingTag = true;
                break;
            }
        }
        assertThat(hasTracingTag)
                .as("@OtelSdkTest must carry @Tag(\"tracing\") for CI filtering")
                .isTrue();
    }

    @Test
    @DisplayName("SDK is operational: can create and finish spans")
    void sdkIsOperational(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        Span span = sdk.getTracer("smoke").spanBuilder("smoke-span").startSpan();
        span.end();
        assertThat(exporter.getFinishedSpanItems())
                .hasSize(1)
                .first()
                .satisfies(s -> assertThat(s.getName()).isEqualTo("smoke-span"));
    }

    @Test
    @DisplayName("OtelSdkExtension has accessible no-arg constructor (guard for @ExtendWith)")
    void noArgConstructorIsAccessible() {
        // Если кто-то удалит no-arg конструктор при рефакторинге —
        // @ExtendWith(OtelSdkExtension.class) упадёт с ExtensionInstantiationException.
        // Этот тест отловит проблему на уровне unit-теста, до рантайма.
        assertThatCode(() -> {
            Constructor<OtelSdkExtension> ctor =
                    OtelSdkExtension.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        }).doesNotThrowAnyException();
    }

    /**
     * METHOD scope isolation вынесена в @Nested с локальным @TestMethodOrder,
     * чтобы @Order не связывал весь внешний класс.
     */
    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("METHOD scope: span must not leak between test methods")
    class MethodScopeIsolationTest {

        @Test
        @Order(1)
        @DisplayName("creates span — exporter must be non-empty at end of this method")
        void createsSpanAndExporterIsNonEmpty(OpenTelemetrySdk sdk,
                                              InMemorySpanExporter exporter) {
            assertThat(exporter.getFinishedSpanItems())
                    .as("exporter must be empty at the start of each test method")
                    .isEmpty();

            sdk.getTracer("isolation-test")
                    .spanBuilder("leak-check-span")
                    .startSpan()
                    .end();

            assertThat(exporter.getFinishedSpanItems())
                    .as("span created in this test must be visible before method ends")
                    .hasSize(1);
        }

        @Test
        @Order(2)
        @DisplayName("exporter is empty — span from @Order(1) must not leak")
        void spanFromPreviousTestDoesNotLeak(InMemorySpanExporter exporter) {
            assertThat(exporter.getFinishedSpanItems())
                    .as("span from previous test must not leak (METHOD scope guard)")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("@Nested: SDK must be available inside nested context (OTel #7919 regression)")
    class NestedScopeTest {

        @Test
        @DisplayName("SDK is available in @Nested test context")
        void sdkAvailableInNestedContext(OpenTelemetrySdk sdk,
                                         InMemorySpanExporter exporter) {
            // OTel upstream issue #7919: AfterAllCallback закрывает SDK преждевременно.
            // OtelSdkExtension использует CloseableResource — этот тест проверяет,
            // что SDK не закрыт к моменту выполнения @Nested-метода.
            assertThat(sdk).isNotNull();
            assertThat(exporter).isNotNull();
            sdk.getTracer("nested-smoke").spanBuilder("nested-span").startSpan().end();
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        }
    }
}
