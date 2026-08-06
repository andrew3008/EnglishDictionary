package space.br1440.platform.tracing.autoconfigure.control.configserver;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;

import lombok.extern.slf4j.Slf4j;

import org.springframework.util.CollectionUtils;

@Slf4j
public final class ConfigServerTracingPolicyAdapter {

    private static final String POLICY_KEY_PREFIX = "platform.tracing.policy.";

    private final TracingPolicyRefreshCoalescer coalescer;

    public ConfigServerTracingPolicyAdapter(TracingPolicyRefreshCoalescer coalescer) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        Objects.requireNonNull(event, "event");

        List<String> relevantKeys = relevantPolicyKeys(event.getKeys());
        if (relevantKeys.isEmpty()) {
            return;
        }

        log.debug(
                "EnvironmentChangeEvent changed {} platform.tracing.policy.* key(s) {}; triggering coalesced refresh",
                relevantKeys.size(),
                relevantKeys
        );

        coalescer.onRefreshEvent();
    }

    private static List<String> relevantPolicyKeys(Set<String> changedKeys) {
        if (CollectionUtils.isEmpty(changedKeys)) {
            return List.of();
        }

        return changedKeys.stream()
                .filter(Objects::nonNull)
                .filter(key -> key.startsWith(POLICY_KEY_PREFIX))
                .sorted()
                .toList();
    }
}
