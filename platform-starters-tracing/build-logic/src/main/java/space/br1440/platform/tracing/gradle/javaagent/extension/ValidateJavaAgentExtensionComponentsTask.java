package space.br1440.platform.tracing.gradle.javaagent.extension;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import space.br1440.platform.tracing.gradle.javaagent.ComponentIdentity;

@DisableCachingByDefault(because = "The task validates a small resolved component identity set")
public abstract class ValidateJavaAgentExtensionComponentsTask extends DefaultTask {

    @Input
    public abstract SetProperty<String> getResolvedComponents();

    @Input
    public abstract SetProperty<String> getPackagedComponents();

    @Input
    public abstract SetProperty<String> getFollowedModules();

    @Input
    public abstract SetProperty<String> getProvidedComponents();

    @TaskAction
    public void validate() {
        List<String> violations = violations(
                getResolvedComponents().get(),
                getPackagedComponents().get(),
                getFollowedModules().get(),
                getProvidedComponents().get()
        );

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Javaagent extension component policy violations (%d):\n- %s"
                            .formatted(violations.size(), String.join("\n- ", violations))
            );
        }
    }

    static List<String> violations(Set<String> resolved,
                                   Set<String> packaged,
                                   Set<String> followed,
                                   Set<String> provided) {
        SortedSet<ComponentIdentity> resolvedIdentities = identitiesOf(resolved);
        SortedSet<ComponentIdentity> packagedIdentities = identitiesOf(packaged);
        SortedSet<String> followedModules = modulesOf(followed);
        SortedSet<ComponentIdentity> providedIdentities = identitiesOf(provided);
        SortedMap<String, SortedSet<ComponentIdentity>> resolvedByModule = byModule(resolvedIdentities);
        List<String> violations = new ArrayList<>();

        for (ComponentIdentity component : packagedIdentities) {
            if (providedIdentities.contains(component)) {
                violations.add(
                        "component declared in both packaged and provided lists: %s".formatted(component.value())
                );
            }
        }
        addFollowedConflicts(violations, "packaged", packagedIdentities, followedModules);
        addFollowedConflicts(violations, "provided", providedIdentities, followedModules);

        for (ComponentIdentity component : resolvedIdentities) {
            if (!packagedIdentities.contains(component)
                    && !providedIdentities.contains(component)
                    && !followedModules.contains(component.module())) {
                violations.add(
                        "unapproved runtime component %s (add to packaged, provided or followed list)"
                                .formatted(component.value())
                );
            }
        }

        addMissingComponents(violations, "packaged", packagedIdentities, resolvedByModule);
        addMissingComponents(violations, "provided", providedIdentities, resolvedByModule);
        for (String module : followedModules) {
            if (!resolvedByModule.containsKey(module)) {
                violations.add("followed module not on runtimeClasspath: %s".formatted(module));
            }
        }

        return List.copyOf(violations);
    }

    private static SortedSet<ComponentIdentity> identitiesOf(Set<String> values) {
        SortedSet<ComponentIdentity> identities = new TreeSet<>();
        values.forEach(value -> identities.add(ComponentIdentity.parse(value)));
        return identities;
    }

    private static SortedSet<String> modulesOf(Set<String> values) {
        SortedSet<String> modules = new TreeSet<>();
        values.forEach(value -> modules.add(ComponentIdentity.parseModule(value)));
        return modules;
    }

    private static SortedMap<String, SortedSet<ComponentIdentity>> byModule(Set<ComponentIdentity> components) {
        SortedMap<String, SortedSet<ComponentIdentity>> index = new TreeMap<>();
        components.forEach(component ->
                index.computeIfAbsent(component.module(), module -> new TreeSet<>())
                        .add(component)
        );
        return index;
    }

    private static void addFollowedConflicts(List<String> violations,
                                             String category,
                                             Set<ComponentIdentity> components,
                                             Set<String> followed) {
        for (ComponentIdentity component : components) {
            if (followed.contains(component.module())) {
                violations.add(
                        "module declared in both %1$s and followed lists: %2$s (%1$s component: %3$s)"
                                .formatted(category, component.module(), component.value())
                );
            }
        }
    }

    private static void addMissingComponents(List<String> violations,
                                             String category,
                                             Set<ComponentIdentity> approved,
                                             SortedMap<String, SortedSet<ComponentIdentity>> resolvedByModule) {
        for (ComponentIdentity component : approved) {
            SortedSet<ComponentIdentity> sameModule = resolvedByModule.getOrDefault(
                    component.module(),
                    Collections.emptySortedSet()
            );

            if (sameModule.contains(component)) {
                continue;
            }

            String resolvedInstead = sameModule.isEmpty() ? "" : sameModule.stream()
                    .map(ComponentIdentity::value)
                    .collect(joining(", ", "; resolved instead: ", ""));
            violations.add(
                    "%s component not on runtimeClasspath: %s%s"
                            .formatted(category, component.value(), resolvedInstead)
            );
        }
    }
}
