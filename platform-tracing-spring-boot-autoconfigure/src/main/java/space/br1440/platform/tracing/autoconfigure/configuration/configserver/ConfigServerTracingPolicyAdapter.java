package space.br1440.platform.tracing.autoconfigure.configuration.configserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;

/**
 * Config Server authority adapter.
 * <p>
 * Responds to {@link EnvironmentChangeEvent} as a trigger only.
 * Keys from the event are NOT used as patch targets.
 * Delegates full-snapshot rebuild to {@link TracingPolicyRefreshCoalescer}.
 * Does NOT own accepted state.
 * Does NOT register any Actuator mutation surface.
 */
public final class ConfigServerTracingPolicyAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConfigServerTracingPolicyAdapter.class);

    private final TracingPolicyRefreshCoalescer coalescer;

    public ConfigServerTracingPolicyAdapter(TracingPolicyRefreshCoalescer coalescer) {
        this.coalescer = coalescer;
    }

    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        boolean relevant = event.getKeys().stream()
                .anyMatch(k -> k.startsWith("platform.tracing.policy."));
        if (!relevant) return;
        log.debug("EnvironmentChangeEvent with {} relevant policy keys; triggering coalesced refresh.",
                event.getKeys().stream().filter(k -> k.startsWith("platform.tracing.policy.")).count());
        coalescer.onRefreshEvent();
    }
}
