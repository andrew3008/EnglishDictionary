package space.br1440.platform.tracing.autoconfigure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;

class TracingPropertyAdmissionTest {

    private static final String SECRET = "0.25-secret";
    private static final String APPLICATION_SOURCE = "Config resource 'application.yaml' via location 'classpath:/'";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingControlChannelAutoConfiguration.class));

    @Test
    void agentKeyInConfigServerFailsStartupWithoutEchoingTheValue() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("configserver:app", Map.of("platform.tracing.sampling.ratio", SECRET)))
                )
                .withPropertyValues("platform.tracing.control.channel=CONFIG_SERVER")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = failureText(context.getStartupFailure());
                    assertThat(message).contains("owned by AGENT");
                    assertThat(message).contains("platform.tracing.sampling.ratio");
                    assertThat(message).contains("configserver:app");
                    assertThat(message).doesNotContain(SECRET);
                });
    }

    @Test
    void controlChannelFromConfigServerFailsStartup() {
        // given
        MapPropertySource source = new MapPropertySource(
                "configserver:app",
                Map.of(TracingBootstrapPropertyNames.CONTROL_CHANNEL, "CONFIG_SERVER")
        );

        // when
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source))
                .run(context -> {
                    // then
                    assertThat(context).hasFailed();
                    String message = failureText(context.getStartupFailure());
                    assertThat(message).contains("platform.tracing.control.channel");
                    assertThat(message).contains("configserver:app");
                });
    }

    @Test
    void policyKeyFromProcessIsRejectedWhenChannelIsConfigServer() {
        // given
        MockEnvironment environment = environment(TracingControlChannel.CONFIG_SERVER);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("PLATFORM_TRACING_POLICY_SAMPLING_RATIO", SECRET))
        );

        // when
        String message = describe(environment, TracingControlChannel.CONFIG_SERVER);

        // then
        assertThat(message).contains("owned by POLICY");
        assertThat(message).contains("platform.tracing.policy.sampling.ratio");
        assertThat(message).contains("source='systemEnvironment'");
        assertThat(message).doesNotContain(SECRET);
    }

    @Test
    void policyKeyFromConfigServerIsRejectedWhenChannelIsNotConfigServer() {
        // given
        MockEnvironment none = sourced("configserver:app", Map.of("platform.tracing.policy.sampling.ratio", SECRET));
        MockEnvironment actuator = sourced("configserver:app", Map.of("platform.tracing.policy.sampling.ratio", SECRET));

        // when
        String noneMessage = describe(none, TracingControlChannel.NONE);
        String actuatorMessage = describe(actuator, TracingControlChannel.ACTUATOR);

        // then
        assertThat(noneMessage).contains("when control channel is NONE").doesNotContain(SECRET);
        assertThat(actuatorMessage).contains("when control channel is ACTUATOR").doesNotContain(SECRET);
    }

    @Test
    void policyKeyFromApplicationSourceIsRejectedWhenChannelIsConfigServer() {
        // given
        MockEnvironment environment = sourced(APPLICATION_SOURCE, Map.of("platform.tracing.policy.sampling.ratio", SECRET));

        // when
        String message = describe(environment, TracingControlChannel.CONFIG_SERVER);

        // then
        assertThat(message).contains("owned by POLICY");
        assertThat(message).contains("source='" + APPLICATION_SOURCE + "'");
        assertThat(message).doesNotContain(SECRET);
    }

    @Test
    void springNamesFromApplicationSourceAreAdmitted() {
        // given
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("platform.tracing.propagation.outbound.trusted-host-patterns[0]", "api.internal");
        values.put("platform.tracing.request-id-header.enabled", "false");
        MockEnvironment environment = sourced(APPLICATION_SOURCE, values);

        // when
        List<TracingPropertyAdmission.Violation> violations = TracingPropertyAdmission.check(
                environment,
                TracingControlChannel.NONE
        );

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void agentKeyFromSystemPropertiesIsAdmittedAndCommandLineIsRejected() {
        // given
        MockEnvironment systemProperties = environment(TracingControlChannel.NONE);
        systemProperties.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of("platform.tracing.sampling.ratio", SECRET))
        );

        MockEnvironment commandLine = environment(TracingControlChannel.NONE);
        SimpleCommandLinePropertySource source = new SimpleCommandLinePropertySource("--platform.tracing.sampling.ratio=" + SECRET);
        commandLine.getPropertySources().addFirst(source);

        // when
        List<TracingPropertyAdmission.Violation> admitted = TracingPropertyAdmission.check(
                systemProperties,
                TracingControlChannel.NONE
        );
        String rejected = describe(commandLine, TracingControlChannel.NONE);

        // then
        assertThat(admitted).isEmpty();
        assertThat(rejected).contains("owned by AGENT");
        assertThat(rejected).contains("source='commandLineArgs'");
        assertThat(rejected).doesNotContain(SECRET);
    }

    @Test
    void routeKeyWithSlashIsReportedInItsRawForm() {
        // given
        String rawName = "platform.tracing.policy.sampling.route-ratios./work/orders";
        MockEnvironment environment = sourced(APPLICATION_SOURCE, Map.of(rawName, SECRET));

        // when
        String message = describe(environment, TracingControlChannel.CONFIG_SERVER);

        // then
        assertThat(message).contains(rawName);
        assertThat(message).doesNotContain("workorders");
        assertThat(message).doesNotContain(SECRET);
    }

    private static MockEnvironment environment(TracingControlChannel channel) {
        return new MockEnvironment().withProperty(TracingBootstrapPropertyNames.CONTROL_CHANNEL, channel.name());
    }

    private static MockEnvironment sourced(String sourceName, Map<String, Object> values) {
        MockEnvironment environment = environment(TracingControlChannel.CONFIG_SERVER);
        environment.getPropertySources().addFirst(new MapPropertySource(sourceName, values));
        return environment;
    }

    private static String describe(MockEnvironment environment, TracingControlChannel channel) {
        List<TracingPropertyAdmission.Violation> violations = TracingPropertyAdmission.check(environment, channel);
        assertThat(violations).isNotEmpty();
        return TracingPropertyAdmission.describe(channel, violations);
    }

    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();

        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                text.append(current.getMessage());
            }

            current = current.getCause();
        }

        return text.toString();
    }
}
