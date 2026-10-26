package space.br1440.platform.tracing.gradle.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class ComponentIdentityTest {

    @Test
    void formats_absolute_project_path() {
        // given
        String path = ":a:b";

        // when
        String value = ComponentIdentity.project(path).value();

        // then
        assertThat(value).isEqualTo("project::a:b");
    }

    @Test
    void rejects_relative_project_path() {
        // given
        String path = "a:b";

        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.project(path));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void extracts_module_coordinate() {
        // given
        ComponentIdentity identity = ComponentIdentity.parse("g:m:v");

        // when
        String module = identity.module();

        // then
        assertThat(module).isEqualTo("g:m");
    }

    @Test
    void keeps_project_identity_as_module() {
        // given
        ComponentIdentity identity = ComponentIdentity.parse("project::a:b");

        // when
        String module = identity.module();

        // then
        assertThat(module).isEqualTo(identity.value());
    }

    @Test
    void rejects_module_without_version() {
        // given
        String value = "g:m";

        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.parse(value));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group, module and version");
    }

    @Test
    void parses_module_coordinate_matching_resolved_module() {
        // given
        ComponentIdentity resolved = ComponentIdentity.parse("g:m:v");

        // when
        String module = ComponentIdentity.parseModule("g:m");

        // then
        assertThat(module).isEqualTo(resolved.module());
    }

    @Test
    void parses_project_module_matching_resolved_project() {
        // given
        ComponentIdentity resolved = ComponentIdentity.project(":a:b");

        // when
        String module = ComponentIdentity.parseModule("project::a:b");

        // then
        assertThat(module).isEqualTo(resolved.module());
    }

    @Test
    void rejects_module_coordinate_with_version() {
        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.parseModule("g:m:v"));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group and module");
    }

    @Test
    void rejects_module_coordinate_with_empty_part() {
        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.parseModule("g:"));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be non-empty");
    }

    @Test
    void rejects_relative_project_module_coordinate() {
        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.parseModule("project:a:b"));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void rejects_unsupported_component_identifier() {
        // given
        ComponentIdentifier component = new ComponentIdentifier() {
            @Override
            @NotNull
            public String getDisplayName() {
                return "opaque-component";
            }

            @Override
            public String toString() {
                return getDisplayName();
            }
        };

        // when
        Throwable failure = catchThrowable(() -> ComponentIdentity.of(component));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessage("Unsupported component identifier: opaque-component");
    }
}
