package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BoundPropertiesTrackingBindHandler;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;

class SpringPropertyBindingNamesTest {

    @Test
    void boundNamesIncludeIndexedElementsAndNestedScalarsButNotTheRoot() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.propagation.outbound.trusted-host-patterns[0]", "api.internal")
                .withProperty("platform.tracing.kafka.trusted-topic-patterns[0]", "orders")
                .withProperty("platform.tracing.propagation.outbound.enabled", "true")
                .withProperty("platform.tracing.request-id-header.enabled", "false")
                .withProperty("platform.tracing.span-attributes.validation.level", "ERROR");
        List<String> recorded = new ArrayList<>();

        // when
        Binder.get(environment).bind(
                "platform.tracing",
                Bindable.of(TracingProperties.class),
                new BoundPropertiesTrackingBindHandler(property -> recorded.add(property.getName().toString()))
        );

        // then
        assertThat(recorded).contains(
                "platform.tracing.propagation.outbound.trusted-host-patterns[0]",
                "platform.tracing.kafka.trusted-topic-patterns[0]",
                "platform.tracing.propagation.outbound.enabled",
                "platform.tracing.request-id-header.enabled",
                "platform.tracing.span-attributes.validation.level"
        );
        assertThat(recorded).doesNotContain(
                "platform.tracing",
                "platform.tracing.propagation",
                "platform.tracing.propagation.outbound",
                "platform.tracing.kafka",
                "platform.tracing.span-attributes",
                "platform.tracing.span-attributes.validation",
                "platform.tracing.exception-recording.include-message"
        );
    }

    @Test
    void conversionFailurePropagatesFromTheBindHandler() {
        // given
        MockEnvironment environment = new MockEnvironment()
                .withProperty("platform.tracing.span-attributes.validation.level", "not-a-level");

        // when
        // then
        assertThatThrownBy(() -> Binder.get(environment).bind(
                "platform.tracing",
                Bindable.of(TracingProperties.class),
                new BoundPropertiesTrackingBindHandler(property -> {
                })
        )).isInstanceOf(BindException.class);
    }
}
