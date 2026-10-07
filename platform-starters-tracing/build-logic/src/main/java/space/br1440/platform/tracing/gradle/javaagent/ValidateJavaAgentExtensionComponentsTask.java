package space.br1440.platform.tracing.gradle.javaagent;

import java.util.Set;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "The task validates a small resolved component identity set")
public abstract class ValidateJavaAgentExtensionComponentsTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getResolvedComponents();

    @Input
    public abstract SetProperty<String> getPackagedComponents();

    @Input
    public abstract SetProperty<String> getFollowedPackagedModules();

    @Input
    public abstract SetProperty<String> getProvidedComponents();

    @TaskAction
    public void validate() {
        Set<String> resolved = new TreeSet<>(getResolvedComponents().get());
        Set<String> packaged = getPackagedComponents().get();
        Set<String> followed = getFollowedPackagedModules().get();
        Set<String> provided = getProvidedComponents().get();
        resolved.forEach(component -> classify(component, packaged, followed, provided));
    }

    public static ComponentDisposition classify(String component,
                                                Set<String> packagedComponents,
                                                Set<String> followedPackagedModules,
                                                Set<String> providedComponents) {
        if (packagedComponents.contains(component)
                || followedPackagedModules.contains(moduleOf(component))) {
            return ComponentDisposition.PACKAGED;
        }

        if (providedComponents.contains(component)) {
            return ComponentDisposition.PROVIDED;
        }

        throw new GradleException("Unapproved javaagent extension runtime component: " + component);
    }

    private static String moduleOf(String component) {
        if (component.startsWith("project:")) {
            return component;
        }

        int versionSeparator = component.lastIndexOf(':');
        return (versionSeparator < 0) ? component : component.substring(0, versionSeparator);
    }

    public enum ComponentDisposition {
        PACKAGED,
        PROVIDED
    }
}
