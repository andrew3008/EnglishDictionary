package space.br1440.platform.tracing.test.junit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composite JUnit 5 аннотация для стандартных интеграционных тестов OpenTelemetry SDK.
 *
 * <p>Объединяет {@code @ExtendWith(OtelSdkExtension.class)} и {@code @Tag("tracing")}
 * в одном декораторе.
 *
 * <h2>Когда использовать</h2>
 * <p>Используйте эту аннотацию, когда выполнены все три условия:
 * <ol>
 *   <li>Нужен <strong>METHOD scope</strong> — свежий SDK и exporter для каждого тест-метода.</li>
 *   <li>Нет кастомного {@code Sampler}, {@code SpanProcessor} или {@code Resource}.</li>
 *   <li>Нет необходимости в {@code CLASS} или {@code SHARED_NESTED} scope.</li>
 * </ol>
 *
 * <pre>{@code
 * @OtelSdkTest
 * class MyTracingTest {
 *
 *     @Test
 *     void spanIsCreated(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
 *         sdk.getTracer("test").spanBuilder("op").startSpan().end();
 *         assertThat(exporter.getFinishedSpanItems()).hasSize(1);
 *     }
 * }
 * }</pre>
 *
 * <h2>Когда НЕ использовать</h2>
 * <ul>
 *   <li>Нужен {@code CLASS} или {@code SHARED_NESTED} scope — используйте
 *       {@code @RegisterExtension static OtelSdkExtension ext = OtelSdkExtension.classScope();}.</li>
 *   <li>Нужен кастомный {@code Sampler} / {@code SpanProcessor} — используйте
 *       {@code OtelSdkExtension.builder().sampler(...).build()}.</li>
 * </ul>
 *
 * <h2>Антипаттерн: двойная регистрация</h2>
 * <p>Не комбинируйте {@code @OtelSdkTest} с
 * {@code @RegisterExtension static OtelSdkExtension} в одном тест-классе.
 * JUnit зарегистрирует {@code OtelSdkExtension} дважды, что приводит к
 * {@code ParameterResolutionException} (два кандидата для инжекции параметра)
 * или к silent Store corruption (второй экземпляр перезапишет ресурс первого
 * в {@code ExtensionContext.Store} без предупреждения).
 *
 * <h2>@Inherited — область применения</h2>
 * <p>{@code @Inherited} полезен для иерархии {@code extends BaseTest}, где
 * {@code BaseTest} помечен {@code @OtelSdkTest}: наследующий класс получает аннотацию автоматически.
 * Для вложенных {@code @Nested}-классов наследование extension обеспечивается
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(OtelSdkExtension.class)
@Tag("tracing")
public @interface OtelSdkTest {
}
