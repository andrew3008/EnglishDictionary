package space.br1440.platform.healthchecks.processor;

import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import space.br1440.platform.healthchecks.common.HealthProbe;

import java.util.*;

public class DynamicHealthEndpointGroups implements HealthEndpointGroups {

    private final HealthEndpointGroups delegate;
    private final Map<String, HealthEndpointGroup> overriddenGroups;

    public DynamicHealthEndpointGroups(HealthEndpointGroups delegate,
                                       Map<HealthProbe, Set<String>> grouped) {
        this.delegate = delegate;
        this.overriddenGroups = buildGroups(delegate, grouped);
    }

    private Map<String, HealthEndpointGroup> buildGroups(HealthEndpointGroups delegate,
                                                         Map<HealthProbe, Set<String>> grouped) {
        Map<String, HealthEndpointGroup> result = new LinkedHashMap<>();

        grouped.forEach((severity, indicatorNames) -> {
            String groupName = severity.name().toLowerCase();
            HealthEndpointGroup existing = delegate.get(groupName);

            Set<String> includes = new LinkedHashSet<>();
            switch (severity) {
                case LIVENESS -> includes.add("livenessState");
                case READINESS -> includes.add("readinessState");
            }
            includes.addAll(indicatorNames);

            result.put(groupName, new DynamicHealthEndpointGroup(existing, includes));
        });

        return result;
    }

    @Override
    public HealthEndpointGroup getPrimary() {
        return delegate.getPrimary();
    }

    @Override
    public Set<String> getNames() {
        Set<String> names = new LinkedHashSet<>(delegate.getNames());
        names.addAll(overriddenGroups.keySet());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public HealthEndpointGroup get(String name) {
        return overriddenGroups.getOrDefault(name, delegate.get(name));
    }
}
