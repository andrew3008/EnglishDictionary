package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LaunchContractPolicyTest {

    private static final String SHA256 = "deadbeef";
    private static final List<String> GENERATED_ENTRYPOINT = List.of("java", "-Xms32m", "-cp", "@/app/jib-classpath-file", "example.Main");

    @Test
    void generated_java_entrypoint_without_conflicts_has_no_violations() {
        // given
        Map<String, String> environment = Map.of("APP_MODE", "test");

        // when
        List<String> violations = LaunchContractPolicy.violations(GENERATED_ENTRYPOINT, environment);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void inherit_entrypoint_is_rejected() {
        // given
        List<String> entrypoint = null;

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains("INHERIT");
    }

    @Test
    void custom_entrypoint_is_rejected_before_its_tokens_are_inspected() {
        // given
        List<String> entrypoint = List.of("/custom-entry", "-javaagent:/opt/other.jar");

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains("customised").contains("/custom-entry");
    }

    @Test
    void empty_entrypoint_is_rejected_as_custom() {
        // given
        List<String> entrypoint = List.of();

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains("customised");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-javaagent:/opt/platform-tracing/agent.jar",
            "-javaagent:/opt/other/agent.jar",
            "-XX:+ManagementServer",
            "-Dcom.sun.management.jmxremote.port=9010",
            "-Dcom.sun.management.config.file=/etc/management.properties",
            "-Dcom.sun.managementX=1",
            "-Dotel.propagators=tracecontext",
            "-Dotel.propagators",
            "-Dotel.javaagent.configuration-file=/app/otel.properties",
            "-Dotel.javaagent.extensions=/app/ext.jar",
            "-Dotel.sdk.disabled=true",
            "-Dplatform.tracing.controlled-agent.expected-sha256=0000"
    })
    void conflicting_jvm_flag_is_reported_with_the_offending_token(String flag) {
        // given
        List<String> entrypoint = List.of("java", flag, "-cp", "@/app/jib-classpath-file", "example.Main");

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains(flag);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-Dotel.propagators.extra=value",
            "-XX:+DisableAttachMechanism",
            "-XX:-DisableAttachMechanism",
            "-agentlib:jdwp=transport=dt_socket,address=*:5005,server=y,suspend=n",
            "-Dplatform.tracing.controlled-agent.expected-sha256-note=x"
    })
    void consumer_owned_flag_is_not_a_violation(String flag) {
        // given
        List<String> entrypoint = List.of("java", flag, "-cp", "@/app/jib-classpath-file", "example.Main");

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "OTEL_SERVICE_NAME", "OTEL_JAVAAGENT_EXTENSIONS"})
    void reserved_environment_name_is_rejected(String name) {
        // given
        Map<String, String> environment = Map.of(name, "value");

        // when
        List<String> violations = LaunchContractPolicy.violations(GENERATED_ENTRYPOINT, environment);

        // then
        assertThat(violations).singleElement().asString().contains(name);
    }

    @Test
    void every_violation_is_reported_not_only_the_first() {
        // given
        List<String> entrypoint = List.of("java", "-XX:+ManagementServer", "-javaagent:/x.jar", "-cp", "cp", "Main");
        Map<String, String> environment = Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g", "OTEL_TRACES_EXPORTER", "none");

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, environment);

        // then
        assertThat(violations).hasSize(4);
    }

    @Test
    void managed_flags_are_inserted_directly_after_the_java_launcher_in_the_fixed_order() {
        // given
        // when
        List<String> entrypoint = LaunchContractPolicy.withManagedFlags(GENERATED_ENTRYPOINT, SHA256);

        // then
        assertThat(entrypoint).containsExactly(
                "java",
                "-javaagent:/opt/platform-tracing/agent.jar",
                "-Dplatform.tracing.controlled-agent.expected-sha256=" + SHA256,
                "-Xms32m",
                "-cp",
                "@/app/jib-classpath-file",
                "example.Main"
        );
    }

    @Test
    void with_managed_flags_does_not_mutate_the_input() {
        // given
        List<String> input = List.of("java", "example.Main");

        // when
        LaunchContractPolicy.withManagedFlags(input, SHA256);

        // then
        assertThat(input).containsExactly("java", "example.Main");
    }
}
