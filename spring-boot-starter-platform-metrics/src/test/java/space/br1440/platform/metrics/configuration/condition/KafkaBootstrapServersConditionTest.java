package space.br1440.platform.metrics.configuration.condition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaBootstrapServersConditionTest {

    private final KafkaBootstrapServersCondition condition = new KafkaBootstrapServersCondition();

    @Mock
    private ConditionContext context;

    @Mock
    private AnnotatedTypeMetadata metadata;

    @Test
    void matchesWhenGlobalBootstrapServersPropertyIsSet() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.kafka.bootstrap-servers", "localhost:9092");
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome = condition.getMatchOutcome(context, metadata);

        assertThat(outcome.isMatch()).isTrue();
        assertThat(outcome.getMessage()).contains("spring.kafka.bootstrap-servers");
    }

    @Test
    void matchesWhenOnlyStreamsBootstrapServersPropertyIsSet() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.kafka.streams.bootstrap-servers", "streams:9093");
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome = condition.getMatchOutcome(context, metadata);

        assertThat(outcome.isMatch()).isTrue();
        assertThat(outcome.getMessage()).contains("spring.kafka.streams.bootstrap-servers");
    }

    @Test
    void noMatchWhenNoBootstrapKeysAndMessageListsAllFourKeys() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment());

        ConditionOutcome outcome = condition.getMatchOutcome(context, metadata);

        assertThat(outcome.isMatch()).isFalse();
        String message = outcome.getMessage();
        assertThat(message)
                .contains("spring.kafka.bootstrap-servers")
                .contains("spring.kafka.producer.bootstrap-servers")
                .contains("spring.kafka.consumer.bootstrap-servers")
                .contains("spring.kafka.streams.bootstrap-servers");
    }

    @Test
    void configurationPhaseIsParseConfiguration() {
        assertThat(condition.getConfigurationPhase()).isEqualTo(ConfigurationCondition.ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @Test
    void noMatchWhenBootstrapValueIsBlank() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.kafka.bootstrap-servers", "   ");
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome = condition.getMatchOutcome(context, metadata);

        assertThat(outcome.isMatch()).isFalse();
    }

    @Test
    void matchesWithStandardEnvironmentAndMapPropertySource() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "test",
                Collections.singletonMap("spring.kafka.consumer.bootstrap-servers", "c:9092")));
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome = condition.getMatchOutcome(context, metadata);

        assertThat(outcome.isMatch()).isTrue();
    }

    @Test
    void noMatchWhenContextIsNull() {
        // Архитектурное требование: null-контекст не прервёт загрузку микросервиса, тихо возвращает noMatch.
        ConditionOutcome outcome = condition.getMatchOutcome(null, metadata);

        assertThat(outcome.isMatch()).isFalse();
        assertThat(outcome.getMessage()).contains("environment is not available");
    }
}
