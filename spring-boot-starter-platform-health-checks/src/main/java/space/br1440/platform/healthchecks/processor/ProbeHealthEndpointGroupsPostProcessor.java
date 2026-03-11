package space.br1440.platform.healthchecks.processor;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthEndpointGroupsPostProcessor;
import org.springframework.context.ApplicationContext;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.ProbeAware;

import java.util.*;

@RequiredArgsConstructor
public class ProbeHealthEndpointGroupsPostProcessor implements HealthEndpointGroupsPostProcessor {

    private final ApplicationContext context;

    @Override
    public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
        Map<String, ProbeAware> severityBeans = context.getBeansOfType(ProbeAware.class);

        Map<HealthProbe, Set<String>> grouped = new EnumMap<>(HealthProbe.class);
        severityBeans.forEach((beanName, bean) ->
                bean.getProbes().forEach(severity ->
                        grouped.computeIfAbsent(severity, k -> new LinkedHashSet<>())
                                .add(beanName)
                )
        );

        return new DynamicHealthEndpointGroups(groups, grouped);
    }
}
