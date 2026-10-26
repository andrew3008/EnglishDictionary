package space.br1440.platform.tracing.gradle.javaagent.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.gradle.javaagent.ComponentIdentity;

class ValidateJavaAgentExtensionComponentsTaskTest {

    private static final Set<String> PACKAGED = Set.of(
            ComponentIdentity.project(":platform:tracing-api").value(),
            "com.google.protobuf:protobuf-java:4.35.1"
    );
    private static final Set<String> FOLLOWED = Set.of(
            "jakarta.annotation:jakarta.annotation-api"
    );
    private static final Set<String> PROVIDED = Set.of(
            "io.opentelemetry:opentelemetry-api:1.64.0"
    );
    private static final Set<String> RESOLVED = Set.of(
            ComponentIdentity.project(":platform:tracing-api").value(),
            "com.google.protobuf:protobuf-java:4.35.1",
            "jakarta.annotation:jakarta.annotation-api:2.1.1",
            "io.opentelemetry:opentelemetry-api:1.64.0"
    );

    @Test
    void complete_approved_component_set_has_no_violations() {
        // when
        List<String> violations = violations(RESOLVED, PACKAGED, FOLLOWED, PROVIDED);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void aggregates_different_violation_classes_in_stable_order() {
        // given
        Set<String> resolved = Set.of(
                "org.example:unexpected:1.0.0",
                "io.opentelemetry:opentelemetry-api:1.64.0"
        );
        Set<String> packaged = Set.of("com.google.protobuf:protobuf-java:4.35.1");

        // when
        List<String> violations = violations(resolved, packaged, FOLLOWED, PROVIDED);

        // then
        assertThat(violations).containsExactly(
                "unapproved runtime component org.example:unexpected:1.0.0 "
                        + "(add to packaged, provided or followed list)",
                "packaged component not on runtimeClasspath: com.google.protobuf:protobuf-java:4.35.1",
                "followed module not on runtimeClasspath: jakarta.annotation:jakarta.annotation-api"
        );
    }

    @Test
    void reports_resolved_version_instead_of_missing_packaged_version() {
        // given
        Set<String> resolved = Set.of("com.google.protobuf:protobuf-java:4.35.2");
        Set<String> packaged = Set.of("com.google.protobuf:protobuf-java:4.35.1");

        // when
        List<String> violations = violations(resolved, packaged, Set.of(), Set.of());

        // then
        assertThat(violations).containsExactly(
                "unapproved runtime component com.google.protobuf:protobuf-java:4.35.2 "
                        + "(add to packaged, provided or followed list)",
                "packaged component not on runtimeClasspath: com.google.protobuf:protobuf-java:4.35.1; "
                        + "resolved instead: com.google.protobuf:protobuf-java:4.35.2"
        );
    }

    @Test
    void reports_packaged_and_provided_policy_conflict() {
        // given
        Set<String> component = Set.of("org.example:shared:1.0.0");

        // when
        List<String> violations = violations(component, component, Set.of(), component);

        // then
        assertThat(violations).containsExactly(
                "component declared in both packaged and provided lists: org.example:shared:1.0.0"
        );
    }

    @Test
    void reports_packaged_and_followed_policy_conflict() {
        // given
        Set<String> resolved = Set.of("org.example:shared:1.0.0");
        Set<String> packaged = Set.of("org.example:shared:1.0.0");
        Set<String> followed = Set.of("org.example:shared");

        // when
        List<String> violations = violations(resolved, packaged, followed, Set.of());

        // then
        assertThat(violations).containsExactly(
                "module declared in both packaged and followed lists: org.example:shared "
                        + "(packaged component: org.example:shared:1.0.0)"
        );
    }

    @Test
    void reports_every_conflict_of_contradictory_policy_in_stable_order() {
        // given
        Set<String> component = Set.of("org.example:shared:1.0.0");
        Set<String> followed = Set.of("org.example:shared");

        // when
        List<String> violations = violations(Set.of(), component, followed, component);

        // then
        assertThat(violations).containsExactly(
                "component declared in both packaged and provided lists: org.example:shared:1.0.0",
                "module declared in both packaged and followed lists: org.example:shared "
                        + "(packaged component: org.example:shared:1.0.0)",
                "module declared in both provided and followed lists: org.example:shared "
                        + "(provided component: org.example:shared:1.0.0)",
                "packaged component not on runtimeClasspath: org.example:shared:1.0.0",
                "provided component not on runtimeClasspath: org.example:shared:1.0.0",
                "followed module not on runtimeClasspath: org.example:shared"
        );
    }

    @Test
    void accepts_followed_project_module() {
        // given
        String project = ComponentIdentity.project(":platform:followed").value();

        // when
        List<String> violations = violations(Set.of(project), Set.of(), Set.of(project), Set.of());

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void accepts_followed_module_in_any_resolved_version() {
        // given
        Set<String> followed = Set.of("jakarta.annotation:jakarta.annotation-api");

        // when
        List<String> firstVersion = violations(
                Set.of("jakarta.annotation:jakarta.annotation-api:1.3.5"),
                Set.of(),
                followed,
                Set.of()
        );
        List<String> secondVersion = violations(
                Set.of("jakarta.annotation:jakarta.annotation-api:2.1.1"),
                Set.of(),
                followed,
                Set.of()
        );

        // then
        assertThat(firstVersion).isEmpty();
        assertThat(secondVersion).isEmpty();
    }

    @Test
    void reports_missing_followed_module() {
        // when
        List<String> violations = violations(
                Set.of(),
                Set.of(),
                Set.of("jakarta.annotation:jakarta.annotation-api"),
                Set.of()
        );

        // then
        assertThat(violations).containsExactly(
                "followed module not on runtimeClasspath: jakarta.annotation:jakarta.annotation-api"
        );
    }

    private static List<String> violations(Set<String> resolved,
                                           Set<String> packaged,
                                           Set<String> followed,
                                           Set<String> provided) {

        return ValidateJavaAgentExtensionComponentsTask.violations(resolved, packaged, followed, provided);
    }
}
