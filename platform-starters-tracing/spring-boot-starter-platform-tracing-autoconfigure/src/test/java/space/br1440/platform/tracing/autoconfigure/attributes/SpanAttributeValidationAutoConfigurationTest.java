package space.br1440.platform.tracing.autoconfigure.attributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolationException;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;
import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;

import io.opentelemetry.api.common.Attributes;

class SpanAttributeValidationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpanAttributeValidationAutoConfiguration.class));

    @Test
    void defaultLevelIsWarnAndBeanIsUnique() {
        contextRunner.run(context -> {
            // given / when / then
            assertThat(context).hasSingleBean(SpanAttributeValidator.class);
            assertThat(context).hasBean("platformSpanAttributeValidator");
            assertThat(context.getBean(SpanAttributeValidator.class).level())
                    .isEqualTo(SpanAttributeValidationLevel.WARNING);
        });
    }

    @Test
    void property_switchesLevelToDisabled() {
        contextRunner
                .withPropertyValues("platform.tracing.span-attributes.validation.level=DISABLED")
                .run(context ->
                        assertThat(context.getBean(SpanAttributeValidator.class).level())
                                .isEqualTo(SpanAttributeValidationLevel.DISABLED));
    }

    @Test
    void property_switchesLevelToError() {
        contextRunner
                .withPropertyValues("platform.tracing.span-attributes.validation.level=ERROR")
                .run(context ->
                        assertThat(context.getBean(SpanAttributeValidator.class).level())
                                .isEqualTo(SpanAttributeValidationLevel.ERROR));
    }

    @Test
    void applicationBeanOfLevelTypeDoesNotOverrideProperty() {
        contextRunner
                .withBean(SpanAttributeValidationLevel.class, () -> SpanAttributeValidationLevel.ERROR)
                .withPropertyValues("platform.tracing.span-attributes.validation.level=WARNING")
                .run(context ->
                        assertThat(context.getBean(SpanAttributeValidator.class).level())
                                .isEqualTo(SpanAttributeValidationLevel.WARNING)
                );
    }

    @Test
    void strictProperty_rejectsInvalidSpecThroughTracingRuntime() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                TracingControlChannelAutoConfiguration.class,
                                TracingRuntimeAutoConfiguration.class,
                                SpanAttributeValidationAutoConfiguration.class
                        )
                )
                .withPropertyValues(
                        "platform.tracing.control.channel=NONE",
                        "platform.tracing.span-attributes.validation.level=ERROR"
                )
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .run(context -> {
                    // given
                    TraceOperations tracing = context.getBean(TraceOperations.class);
                    SpanSpec invalid = SpanSpec.builder("autoconfigure.invalid")
                            .category(SpanCategory.INTERNAL)
                            .reason(SpanSpecReason.LEGACY_INTEGRATION)
                            .build();

                    // when / then
                    assertThatThrownBy(() -> {
                        try (SpanHandle handle = tracing.spans().fromSpec(invalid).start()) {
                            fail("start() must not return " + handle);
                        }
                    })
                            .isInstanceOf(SpanAttributeViolationException.class);
                });
    }

    @Test
    void disabledProperty_doesNotRejectMissingRequiredAttributes() {
        contextRunner
                .withPropertyValues("platform.tracing.span-attributes.validation.level=DISABLED")
                .run(context -> {
                    // given
                    SpanAttributeValidator validator = context.getBean(SpanAttributeValidator.class);

                    // when / then
                    assertThat(validator.validateAndNormalize(
                            SpanCategory.INTERNAL, Attributes.empty(), "test").violations())
                            .isEmpty();
                });
    }
}
