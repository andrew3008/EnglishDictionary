package space.br1440.platform.tracing.gradle.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

class ValidateJavaAgentExtensionComponentsTaskTest {

    private static final Set<String> PACKAGED = Set.of(
            "project::platform:tracing-api",
            "com.google.protobuf:protobuf-java:4.35.1"
    );
    private static final Set<String> FOLLOWED = Set.of(
            "jakarta.annotation:jakarta.annotation-api"
    );
    private static final Set<String> PROVIDED = Set.of(
            "io.opentelemetry:opentelemetry-api:1.64.0"
    );

    @Test
    void accepts_packaged_component_with_approved_version() {
        // when
        var disposition = classify("com.google.protobuf:protobuf-java:4.35.1");

        // then
        assertThat(disposition)
                .isEqualTo(ValidateJavaAgentExtensionComponentsTask.ComponentDisposition.PACKAGED);
    }

    @Test
    void rejects_packaged_module_with_another_version() {
        // expect
        assertThatThrownBy(() -> classify("com.google.protobuf:protobuf-java:4.35.2"))
                .isInstanceOf(GradleException.class)
                .hasMessage("Unapproved javaagent extension runtime component: com.google.protobuf:protobuf-java:4.35.2");
    }

    @Test
    void accepts_provided_agent_api_without_packaging_it() {
        // when
        var disposition = classify("io.opentelemetry:opentelemetry-api:1.64.0");

        // then
        assertThat(disposition)
                .isEqualTo(ValidateJavaAgentExtensionComponentsTask.ComponentDisposition.PROVIDED);
    }

    @Test
    void accepts_followed_module_without_version_pin() {
        // when
        var disposition = classify("jakarta.annotation:jakarta.annotation-api:2.1.1");

        // then
        assertThat(disposition)
                .isEqualTo(ValidateJavaAgentExtensionComponentsTask.ComponentDisposition.PACKAGED);
    }

    @Test
    void rejects_unknown_component() {
        // expect
        assertThatThrownBy(() -> classify("org.example:unexpected:1.0.0"))
                .isInstanceOf(GradleException.class)
                .hasMessage("Unapproved javaagent extension runtime component: org.example:unexpected:1.0.0");
    }

    private static ValidateJavaAgentExtensionComponentsTask.ComponentDisposition classify(String component) {
        return ValidateJavaAgentExtensionComponentsTask.classify(component, PACKAGED, FOLLOWED, PROVIDED);
    }
}
