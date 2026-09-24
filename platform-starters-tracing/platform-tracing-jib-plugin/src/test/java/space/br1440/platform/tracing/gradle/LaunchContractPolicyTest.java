package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
        assertThat(violations).singleElement().asString().contains("not a Jib Java classpath launch").contains("/custom-entry");
    }

    @Test
    void empty_entrypoint_is_rejected_as_custom() {
        // given
        List<String> entrypoint = List.of();

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains("not a Jib Java classpath launch");
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
            "-Dplatform.tracing.controlled-agent.expected-sha256=0000",
            "-Dotel.traces.sampler=always_off",
            "-agentpath:/opt/libagent.so",
            "-Xrunjdwp:transport=dt_socket",
            "-XX:VMOptionsFile=/tmp/options",
            "@/x.args"
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
            "-XX:+DisableAttachMechanism",
            "-XX:-DisableAttachMechanism",
            "-D"
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

    @ParameterizedTest
    @ValueSource(strings = {
            "java|-jar|app.jar",
            "java|-cp|libs/*|Main",
            "java|-cp|/opt/x/../platform-tracing/*|Main",
            "java|-cp|X|-jar|app.jar"
    })
    void launch_form_and_non_canonical_classpath_are_rejected(String tokens) {
        // given
        List<String> entrypoint = List.of(tokens.split("\\|"));

        // when
        ArrayList<String> violations = new ArrayList<>(
                LaunchContractPolicy.violations(entrypoint, Map.of()));
        LaunchContractPolicy.classpathToken(entrypoint).ifPresent(token ->
                violations.addAll(LaunchContractPolicy.classpathElementViolations(token)));

        // then
        assertThat(violations).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java|example.Main",
            "java|-classpath|/app/libs/*|example.Main",
            "java|--class-path|/app/libs/*|example.Main"
    })
    void launch_without_the_jib_classpath_flag_is_rejected(String tokens) {
        // given
        List<String> entrypoint = List.of(tokens.split("\\|"));

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).singleElement().asString().contains("not a Jib Java classpath launch");
    }

    @Test
    void application_argument_after_the_main_class_is_not_a_jvm_flag() {
        // given
        List<String> entrypoint = List.of(
                "java", "-cp", "@/app/jib-classpath-file", "example.Main", "-javaagent:x");

        // when
        List<String> violations = LaunchContractPolicy.violations(entrypoint, Map.of());

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void managed_flags_are_inserted_before_the_classpath_flag() {
        // given
        // when
        List<String> entrypoint = LaunchContractPolicy.withManagedFlags(GENERATED_ENTRYPOINT, SHA256);

        // then
        assertThat(entrypoint).containsExactly(
                "java",
                "-Xms32m",
                "-javaagent:/opt/platform-tracing/agent.jar",
                "-Dplatform.tracing.controlled-agent.expected-sha256=" + SHA256,
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
