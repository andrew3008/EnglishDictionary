package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.semconv.ValidationMode;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.core.enrichment.SpanEnricher;
import space.br1440.platform.tracing.test.semconv.SemconvStrictTestAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link SemanticLayerAutoConfiguration}: дефолт WARN в проде, приоритет бина
 * {@link ValidationMode} (канал {@link SemconvStrictTestAutoConfiguration}) и явный DISABLED.
 */
class SemanticLayerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SemanticLayerAutoConfiguration.class));

    @Test
    void поУмолчаниюРежимWARN() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AttributePolicy.class);
            assertThat(context).hasSingleBean(SpanEnricher.class);
            assertThat(context).hasSingleBean(ExceptionRecorder.class);
            assertThat(context.getBean(AttributePolicy.class).mode()).isEqualTo(ValidationMode.WARN);
        });
    }

    @Test
    void property_переключаетРежимВDISABLED() {
        contextRunner
                .withPropertyValues("platform.tracing.semantic.validation-mode=DISABLED")
                .run(context ->
                        assertThat(context.getBean(AttributePolicy.class).mode()).isEqualTo(ValidationMode.DISABLED));
    }

    @Test
    void бинValidationModeИмеетПриоритетНадProperty() {
        // SemconvStrictTestAutoConfiguration публикует бин ValidationMode.STRICT,
        // который обязан переопределить property WARN.
        contextRunner
                .withUserConfiguration(SemconvStrictTestAutoConfiguration.class)
                .withPropertyValues("platform.tracing.semantic.validation-mode=WARN")
                .run(context ->
                        assertThat(context.getBean(AttributePolicy.class).mode()).isEqualTo(ValidationMode.STRICT));
    }

    @Test
    void флагОтключаетStrictTestConfig() {
        contextRunner
                .withUserConfiguration(SemconvStrictTestAutoConfiguration.class)
                .withPropertyValues("platform.tracing.test.semconv-strict=false")
                .run(context ->
                        assertThat(context.getBean(AttributePolicy.class).mode()).isEqualTo(ValidationMode.WARN));
    }
}
