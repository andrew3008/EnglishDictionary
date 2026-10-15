package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ConfigurationPropertySourceIterationTest {

    private static final String ROUTE_DOTTED = "platform.tracing.policy.sampling.route-ratios./work/orders";
    private static final String ROUTE_BRACKET = "platform.tracing.policy.sampling.route-ratios[/work/health]";

    @Test
    void iterationExposesConfigServerLeafAndMappedEnvironmentName() {
        // given
        Map<String, Object> configServer = new LinkedHashMap<>();
        configServer.put("platform.tracing.policy.sampling.ratio", "0.2");
        configServer.put(ROUTE_DOTTED, "0.25");
        configServer.put(ROUTE_BRACKET, "0.5");
        MapPropertySource configServerSource = new MapPropertySource("configserver:file:/config/application.yaml", configServer);

        Map<String, Object> environmentVariables = new LinkedHashMap<>();
        environmentVariables.put("PLATFORM_TRACING_POLICY_SAMPLING_RATIO", "0.3");
        SystemEnvironmentPropertySource processSource = new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                environmentVariables
        );

        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(processSource);
        environment.getPropertySources().addFirst(configServerSource);

        // when
        List<String> configServerNames = namesFrom(environment, "configserver:file:/config/application.yaml");
        List<String> processNames = namesFrom(environment, StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);

        // then
        assertThat(configServerNames).contains(
                "platform.tracing.policy.sampling.ratio",
                "platform.tracing.policy.sampling.route-ratios.workorders",
                ROUTE_BRACKET
        );
        assertThat(configServerNames).doesNotContain(ROUTE_DOTTED);
        assertThat(ConfigurationPropertyName.adapt(ROUTE_DOTTED, '.').toString())
                .isEqualTo("platform.tracing.policy.sampling.route-ratios.workorders");
        assertThat(configServerSource.getProperty(ROUTE_DOTTED)).isEqualTo("0.25");
        assertThat(processNames).contains("platform.tracing.policy.sampling.ratio");
    }

    @Test
    void iterationSeesNamesAddedToTheSameSourceAfterContextRefresh() {
        // given
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.policy.sampling.enabled", "true");
        MapPropertySource source = new MapPropertySource("configserver:app", values);

        try (ConfigurableApplicationContext context = refreshApplication(source).run()) {
            ContextRefresher refresher = context.getBean(ContextRefresher.class);

            // when
            values.put("platform.tracing.policy.sampling.ration", "0.2");
            refresher.refresh();
            List<String> names = namesFrom(context.getEnvironment(), "configserver:app");

            // then
            assertThat(names).contains(
                    "platform.tracing.policy.sampling.enabled",
                    "platform.tracing.policy.sampling.ration"
            );
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @Import(RefreshAutoConfiguration.class)
    static class RefreshOnlyConfiguration {
    }

    private static SpringApplication refreshApplication(MapPropertySource source) {
        SpringApplication application = new SpringApplication(RefreshOnlyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "spring.cloud.config.enabled", "false",
                "spring.cloud.config.import-check.enabled", "false")
        );
        application.addInitializers(applicationContext ->
                applicationContext.getEnvironment().getPropertySources().addFirst(source)
        );
        return application;
    }

    private static List<String> namesFrom(ConfigurableEnvironment environment, String sourceName) {
        List<String> names = new ArrayList<>();

        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (!(source instanceof IterableConfigurationPropertySource iterable)) {
                continue;
            }

            Object underlying = source.getUnderlyingSource();
            if (!(underlying instanceof PropertySource<?> propertySource) || !sourceName.equals(propertySource.getName())) {
                continue;
            }

            for (ConfigurationPropertyName name : iterable) {
                names.add(name.toString());
            }
        }

        return names;
    }
}
