package space.br1440.platform.tracing.gradle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyAgentClasspathTask extends DefaultTask {

    @Input
    public abstract ListProperty<ResolvedComponentResult> getClasspathRoots();

    @TaskAction
    public void verify() {
        Set<String> offenders = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (ResolvedComponentResult root : getClasspathRoots().get()) {
            walk(root, offenders, unresolved);
        }

        if (!unresolved.isEmpty()) {
            throw new GradleException("""
                    Cannot verify that the controlled Java agent stays off the application classpath: \
                    the dependency graph is incomplete because of unresolved dependencies: %s."""
                    .formatted(String.join(", ", unresolved))
            );
        }

        if (!offenders.isEmpty()) {
            throw new GradleException("""
                    Controlled Java agent %s:%s must not be an application dependency, \
                    but it is reachable from: %s. \
                    The agent is a deployment artifact and is wired by the \
                    platform.tracing-jib plugin only."""
                    .formatted(
                            PlatformTracingJibPlugin.AGENT_GROUP,
                            PlatformTracingJibPlugin.AGENT_MODULE,
                            String.join(", ", offenders)
                    )
            );
        }
    }

    private static void walk(ResolvedComponentResult root, Set<String> offenders, Set<String> unresolved) {
        Set<ComponentIdentifier> visited = new HashSet<>();
        Deque<ResolvedComponentResult> queue = new ArrayDeque<>();
        visited.add(root.getId());
        queue.add(root);

        while (!queue.isEmpty()) {
            ResolvedComponentResult current = queue.removeFirst();
            for (DependencyResult dependency : current.getDependencies()) {
                if (dependency instanceof UnresolvedDependencyResult failed) {
                    unresolved.add("""
                            %s (required by %s)"""
                            .formatted(failed.getAttempted().getDisplayName(), current.getId().getDisplayName()));
                    continue;
                }

                if (!(dependency instanceof ResolvedDependencyResult resolved)) {
                    continue;
                }

                ResolvedComponentResult selected = resolved.getSelected();
                if (isControlledAgent(selected.getId())) {
                    offenders.add("""
                            %s (required by %s)"""
                            .formatted(selected.getId().getDisplayName(), resolved.getFrom().getId().getDisplayName())
                    );
                }

                if (visited.add(selected.getId())) {
                    queue.add(selected);
                }
            }
        }
    }

    private static boolean isControlledAgent(ComponentIdentifier identifier) {
        if (!(identifier instanceof ModuleComponentIdentifier module)) {
            return false;
        }

        return PlatformTracingJibPlugin.AGENT_GROUP.equals(module.getGroup())
                && PlatformTracingJibPlugin.AGENT_MODULE.equals(module.getModule());
    }
}
