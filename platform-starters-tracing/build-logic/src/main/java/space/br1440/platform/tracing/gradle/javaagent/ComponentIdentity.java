package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;

public record ComponentIdentity(String value) implements Comparable<ComponentIdentity> {

    private static final String PROJECT_PREFIX = "project:";

    public ComponentIdentity {
        requireValid(value);
    }

    public static ComponentIdentity project(String absolutePath) {
        requireAbsoluteProjectPath(absolutePath);
        return new ComponentIdentity(PROJECT_PREFIX + absolutePath);
    }

    public static ComponentIdentity module(String group, String module, String version) {
        requireModulePart("group", group);
        requireModulePart("module", module);
        requireModulePart("version", version);
        return new ComponentIdentity(group + ":" + module + ":" + version);
    }

    public static ComponentIdentity of(ComponentIdentifier component) {
        if (component instanceof ProjectComponentIdentifier projectComponent) {
            return project(projectComponent.getProjectPath());
        }

        if (component instanceof ModuleComponentIdentifier moduleComponent) {
            return module(
                    moduleComponent.getGroup(),
                    moduleComponent.getModule(),
                    moduleComponent.getVersion()
            );
        }

        throw new GradleException("Unsupported component identifier: " + component);
    }

    public static ComponentIdentity parse(String value) {
        return new ComponentIdentity(value);
    }

    public static String parseModule(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Module coordinate must not be null");
        }

        if (value.startsWith(PROJECT_PREFIX)) {
            requireAbsoluteProjectPath(value.substring(PROJECT_PREFIX.length()));
            return value;
        }

        String[] parts = value.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Module coordinate must contain group and module: " + value);
        }

        requireModulePart("group", parts[0]);
        requireModulePart("module", parts[1]);
        return value;
    }

    public String module() {
        if (value.startsWith(PROJECT_PREFIX)) {
            return value;
        }

        return value.substring(0, value.lastIndexOf(':'));
    }

    @Override
    public int compareTo(ComponentIdentity other) {
        return value.compareTo(other.value);
    }

    private static void requireValid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Component identity must not be null");
        }

        if (value.startsWith(PROJECT_PREFIX)) {
            requireAbsoluteProjectPath(value.substring(PROJECT_PREFIX.length()));
            return;
        }

        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Module component identity must contain group, module and version: " + value
            );
        }

        requireModulePart("group", parts[0]);
        requireModulePart("module", parts[1]);
        requireModulePart("version", parts[2]);
    }

    private static void requireAbsoluteProjectPath(String absolutePath) {
        if (absolutePath == null || !absolutePath.startsWith(":")) {
            throw new IllegalArgumentException(
                    "Gradle project path must be absolute and start with ':', got: " + absolutePath
            );
        }
    }

    private static void requireModulePart(String name, String value) {
        if (value == null || value.isEmpty() || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "Module component " + name + " must be non-empty and contain no colons, got: " + value
            );
        }
    }
}
