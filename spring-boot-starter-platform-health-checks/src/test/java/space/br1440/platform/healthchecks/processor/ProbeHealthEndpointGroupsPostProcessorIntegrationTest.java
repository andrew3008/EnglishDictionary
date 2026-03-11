package space.br1440.platform.healthchecks.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.ProbeAware;
import space.br1440.platform.healthchecks.configuration.HealthGroupAutoConfiguration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProbeHealthEndpointGroupsPostProcessor: интеграция")
class ProbeHealthEndpointGroupsPostProcessorIntegrationTest {

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = ProbeHealthEndpointGroupsPostProcessorIntegrationTest.MinimalConfig.class
    )
    @DisplayName("1. Регистрация пост-процессора и тип групп")
    class PostProcessorRegistration {

        @Autowired
        private HealthEndpointGroups groups;

        @Autowired(required = false)
        private ProbeHealthEndpointGroupsPostProcessor postProcessor;

        @Test
        @DisplayName("бин ProbeHealthEndpointGroupsPostProcessor присутствует в контексте")
        void postProcessorBeanIsPresent() {
            assertThat(postProcessor).isNotNull();
        }

        @Test
        @DisplayName("HealthEndpointGroups — экземпляр DynamicHealthEndpointGroups после пост-обработки")
        void healthEndpointGroupsIsDynamic() {
            assertThat(groups).isInstanceOf(DynamicHealthEndpointGroups.class);
        }

        @Test
        @DisplayName("getNames() содержит readiness, liveness, startup")
        void groupNamesContainProbeGroups() {
            Set<String> names = groups.getNames();
            assertThat(names)
                    .contains("readiness", "liveness", "startup");
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = ProbeHealthEndpointGroupsPostProcessorIntegrationTest.SingleProbeIndicatorsConfig.class
    )
    @DisplayName("2. Состав групп: индикаторы с одной пробой")
    class SingleProbeGroupMembers {

        @Autowired
        private HealthEndpointGroups groups;

        @Test
        @DisplayName("readiness-группа содержит только индикатор с getProbes() = READINESS")
        void readinessGroupContainsOnlyReadinessIndicator() {
            HealthEndpointGroup readiness = groups.get("readiness");
            assertThat(readiness).isNotNull();

            assertThat(readiness.isMember("readinessState")).isTrue();
            assertThat(readiness.isMember("readinessOnlyIndicator")).isTrue();
            assertThat(readiness.isMember("livenessOnlyIndicator")).isFalse();
            assertThat(readiness.isMember("startupOnlyIndicator")).isFalse();
        }

        @Test
        @DisplayName("liveness-группа содержит только индикатор с getProbes() = LIVENESS")
        void livenessGroupContainsOnlyLivenessIndicator() {
            HealthEndpointGroup liveness = groups.get("liveness");
            assertThat(liveness).isNotNull();

            assertThat(liveness.isMember("livenessState")).isTrue();
            assertThat(liveness.isMember("livenessOnlyIndicator")).isTrue();
            assertThat(liveness.isMember("readinessOnlyIndicator")).isFalse();
            assertThat(liveness.isMember("startupOnlyIndicator")).isFalse();
        }

        @Test
        @DisplayName("startup-группа содержит только индикатор с getProbes() = STARTUP")
        void startupGroupContainsOnlyStartupIndicator() {
            HealthEndpointGroup startup = groups.get("startup");
            assertThat(startup).isNotNull();

            assertThat(startup.isMember("startupOnlyIndicator")).isTrue();
            assertThat(startup.isMember("readinessOnlyIndicator")).isFalse();
            assertThat(startup.isMember("livenessOnlyIndicator")).isFalse();
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = ProbeHealthEndpointGroupsPostProcessorIntegrationTest.MultiProbeIndicatorConfig.class
    )
    @DisplayName("3. Индикатор в нескольких пробах")
    class MultiProbeIndicator {

        @Autowired
        private HealthEndpointGroups groups;

        @Test
        @DisplayName("индикатор с getProbes() = READINESS, LIVENESS входит в обе группы")
        void indicatorInMultipleProbesAppearsInBothGroups() {
            HealthEndpointGroup readiness = groups.get("readiness");
            HealthEndpointGroup liveness = groups.get("liveness");

            assertThat(readiness).isNotNull();
            assertThat(liveness).isNotNull();

            assertThat(readiness.isMember("readinessAndLivenessIndicator")).isTrue();
            assertThat(liveness.isMember("readinessAndLivenessIndicator")).isTrue();
        }

        @Test
        @DisplayName("readiness и liveness группы содержат readinessState / livenessState")
        void groupsIncludeStateContributors() {
            assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
            assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = ProbeHealthEndpointGroupsPostProcessorIntegrationTest.MultipleReadinessConfig.class
    )
    @DisplayName("4. Несколько индикаторов в одной группе (readiness)")
    class MultipleIndicatorsInGroup {

        @Autowired
        private HealthEndpointGroups groups;

        @Test
        @DisplayName("readiness-группа содержит оба индикатора с getProbes() = READINESS")
        void readinessGroupContainsAllReadinessIndicators() {
            HealthEndpointGroup readiness = groups.get("readiness");
            assertThat(readiness).isNotNull();

            assertThat(readiness.isMember("readinessIndicatorA")).isTrue();
            assertThat(readiness.isMember("readinessIndicatorB")).isTrue();
            assertThat(readiness.isMember("readinessState")).isTrue();
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = ProbeHealthEndpointGroupsPostProcessorIntegrationTest.NoProbeAwareBeansConfig.class
    )
    @DisplayName("5. Нет бинов ProbeAware — группы только с state")
    class NoProbeAwareBeans {

        @Autowired
        private HealthEndpointGroups groups;

        @Test
        @DisplayName("readiness-группа существует и содержит только readinessState")
        void readinessGroupExistsWithOnlyState() {
            HealthEndpointGroup readiness = groups.get("readiness");
            assertThat(readiness).isNotNull();
            assertThat(readiness.isMember("readinessState")).isTrue();
        }

        @Test
        @DisplayName("liveness-группа существует и содержит только livenessState")
        void livenessGroupExistsWithOnlyState() {
            HealthEndpointGroup liveness = groups.get("liveness");
            assertThat(liveness).isNotNull();
            assertThat(liveness.isMember("livenessState")).isTrue();
        }
    }

    @SpringBootApplication
    @Import(HealthGroupAutoConfiguration.class)
    static class MinimalConfig {
        // Без ProbeAware бинов — только проверяем тип групп и имена
    }

    @SpringBootApplication
    @Import(HealthGroupAutoConfiguration.class)
    static class SingleProbeIndicatorsConfig {

        @Bean("readinessOnlyIndicator")
        HealthIndicator readinessOnlyIndicator() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.READINESS));
        }

        @Bean("livenessOnlyIndicator")
        HealthIndicator livenessOnlyIndicator() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.LIVENESS));
        }

        @Bean("startupOnlyIndicator")
        HealthIndicator startupOnlyIndicator() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.STARTUP));
        }
    }

    @SpringBootApplication
    @Import(HealthGroupAutoConfiguration.class)
    static class MultiProbeIndicatorConfig {

        @Bean("readinessAndLivenessIndicator")
        HealthIndicator readinessAndLivenessIndicator() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.READINESS, HealthProbe.LIVENESS));
        }
    }

    @SpringBootApplication
    @Import(HealthGroupAutoConfiguration.class)
    static class MultipleReadinessConfig {

        @Bean("readinessIndicatorA")
        HealthIndicator readinessIndicatorA() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.READINESS));
        }

        @Bean("readinessIndicatorB")
        HealthIndicator readinessIndicatorB() {
            return new StubProbeAwareIndicator(Set.of(HealthProbe.READINESS));
        }
    }

    @SpringBootApplication
    @Import(HealthGroupAutoConfiguration.class)
    static class NoProbeAwareBeansConfig {
        // Нет бинов, реализующих ProbeAware — только стандартные группы из delegate
    }

    private static final class StubProbeAwareIndicator implements HealthIndicator, ProbeAware {

        private final Set<HealthProbe> probes;

        StubProbeAwareIndicator(Set<HealthProbe> probes) {
            this.probes = Set.copyOf(probes);
        }

        @Override
        public Health health() {
            return Health.up().withDetail("stub", true).build();
        }

        @Override
        public Set<HealthProbe> getProbes() {
            return probes;
        }
    }
}
