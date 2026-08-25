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
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import space.br1440.platform.common.util.SetUtils;

public abstract class VerifyAgentClasspathTask extends DefaultTask {

    @Input
    public abstract ListProperty<ResolvedComponentResult> getClasspathRoots();

    @TaskAction
    public void verify() {
        Set<String> offenders = new LinkedHashSet<>();
        for (ResolvedComponentResult root : getClasspathRoots().get()) {
            collectOffenders(root, offenders);
        }

        if (!SetUtils.isNullOrEmpty(offenders)) {
            throw new GradleException("""
                    Controlled Java agent %s:%s must not be an application dependency, \
                    but it is reachable from: %s. \
                    The agent is a deployment artifact and is wired by the \
                    platform.tracing-javaagent plugin only."""
                    .formatted(
                            PlatformTracingJavaAgentPlugin.AGENT_GROUP,
                            PlatformTracingJavaAgentPlugin.AGENT_MODULE,
                            String.join(", ", offenders)
                    )
            );
        }
    }

    private static void collectOffenders(ResolvedComponentResult root, Set<String> offenders) {
        Set<ComponentIdentifier> visited = new HashSet<>();

        Deque<ResolvedComponentResult> queue = new ArrayDeque<>();
        visited.add(root.getId());
        queue.add(root);

        while (!queue.isEmpty()) {
            ResolvedComponentResult current = queue.removeFirst();
            for (DependencyResult dependency : current.getDependencies()) {
                if (!(dependency instanceof ResolvedDependencyResult resolved)) {
                    continue;
                }

                ResolvedComponentResult selected = resolved.getSelected();
                if (isControlledAgent(selected.getId())) {
                    offenders.add("""
                            %s (required by %s)"""
                            .formatted(
                                    selected.getId().getDisplayName(),
                                    resolved.getFrom().getId().getDisplayName()
                            )
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

        return PlatformTracingJavaAgentPlugin.AGENT_GROUP.equals(module.getGroup())
                && PlatformTracingJavaAgentPlugin.AGENT_MODULE.equals(module.getModule());
    }
}
