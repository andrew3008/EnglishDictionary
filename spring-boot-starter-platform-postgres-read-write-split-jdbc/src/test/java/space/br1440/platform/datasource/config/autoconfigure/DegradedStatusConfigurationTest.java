package space.br1440.platform.datasource.config.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.datasource.postgres.internal.integration.health.DegradedStatusConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.health.DegradedHealthStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DegradedStatusAssembler")
class DegradedStatusConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(DegradedStatusConfiguration.class);

    @Test
    @DisplayName("StatusAggregator регистрируется и DEGRADED приоритетнее UP")
    void statusAggregator_registeredAndDegradedBeatsUp() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StatusAggregator.class);
            StatusAggregator aggregator = ctx.getBean(StatusAggregator.class);
            Status result = aggregator.getAggregateStatus(Set.of(
                    Status.UP,
                    DegradedHealthStatus.INSTANCE
            ));
            assertThat(result.getCode()).isEqualTo("DEGRADED");
        });
    }

    @Test
    @DisplayName("StatusAggregator: DOWN побеждает DEGRADED")
    void statusAggregator_downBeatsDegraded() {
        runner.run(ctx -> {
            StatusAggregator aggregator = ctx.getBean(StatusAggregator.class);
            Status result = aggregator.getAggregateStatus(Set.of(
                    DegradedHealthStatus.INSTANCE,
                    Status.DOWN
            ));
            assertThat(result).isEqualTo(Status.DOWN);
        });
    }

    @Test
    @DisplayName("HttpCodeStatusMapper регистрируется и DEGRADED → 503 (критичный регресс-тест routing-стартера)")
    void httpCodeMapper_registeredAndDegradedMaps503() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(HttpCodeStatusMapper.class);
            HttpCodeStatusMapper mapper = ctx.getBean(HttpCodeStatusMapper.class);

            assertThat(mapper.getStatusCode(DegradedHealthStatus.INSTANCE)).isEqualTo(503);
            assertThat(mapper.getStatusCode(Status.DOWN)).isEqualTo(503);
            assertThat(mapper.getStatusCode(Status.UP)).isEqualTo(200);
        });
    }

    @Test
    @DisplayName("HttpCodeStatusMapper: DegradedHealthStatus.INSTANCE и new Status(\"DEGRADED\") дают одинаковый HTTP-код")
    void httpCodeMapper_instanceAndNewStatusEquivalent() {
        runner.run(ctx -> {
            HttpCodeStatusMapper mapper = ctx.getBean(HttpCodeStatusMapper.class);
            int codeViaInstance = mapper.getStatusCode(DegradedHealthStatus.INSTANCE);
            int codeViaNew = mapper.getStatusCode(new Status("DEGRADED"));
            assertThat(codeViaInstance).isEqualTo(codeViaNew).isEqualTo(503);
        });
    }

    @Test
    @DisplayName("@ConditionalOnMissingBean: пользовательский StatusAggregator не перезаписывается")
    void statusAggregator_notOverridenWhenUserBeanPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomAggregatorConfig.class, DegradedStatusConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StatusAggregator.class);
                    assertThat(ctx.getBean(StatusAggregator.class))
                            .isInstanceOf(CustomAggregatorConfig.FixedAggregator.class);
                });
    }

    @Test
    @DisplayName("@ConditionalOnMissingBean: пользовательский HttpCodeStatusMapper не перезаписывается")
    void httpCodeMapper_notOverridenWhenUserBeanPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomMapperConfig.class, DegradedStatusConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HttpCodeStatusMapper.class);
                    assertThat(ctx.getBean(HttpCodeStatusMapper.class))
                            .isInstanceOf(CustomMapperConfig.FixedMapper.class);
                });
    }

    @Configuration
    static class CustomAggregatorConfig {
        @Bean
        StatusAggregator statusAggregator() {
            return new FixedAggregator();
        }

        static class FixedAggregator extends SimpleStatusAggregator {
        }
    }

    @Configuration
    static class CustomMapperConfig {
        @Bean
        HttpCodeStatusMapper httpCodeStatusMapper() {
            return new FixedMapper();
        }

        static class FixedMapper extends SimpleHttpCodeStatusMapper {
            FixedMapper() {
                super(java.util.Map.of());
            }
        }
    }
}
