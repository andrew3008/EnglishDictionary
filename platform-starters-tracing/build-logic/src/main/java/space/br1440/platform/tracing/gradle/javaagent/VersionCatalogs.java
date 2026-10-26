package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.provider.Provider;

public final class VersionCatalogs {

    private VersionCatalogs() {
    }

    public static Provider<String> requiredVersion(Project project, String alias) {
        return project.provider(() -> project.getExtensions()
                .getByType(VersionCatalogsExtension.class)
                .named("libs")
                .findVersion(alias)
                .orElseThrow(() -> new GradleException("Missing version catalog entry: " + alias))
                .getRequiredVersion());
    }
}
